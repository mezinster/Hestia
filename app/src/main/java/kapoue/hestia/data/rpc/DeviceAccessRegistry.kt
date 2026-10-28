package kapoue.hestia.data.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Problème d'accès constaté sur un appareil, à signaler à l'utilisateur. */
enum class AccessIssue {
    /** L'appareil n'accepte que le HTTPS et aucune épingle n'est connue. */
    HTTPS_REQUIRED,

    /** Le certificat présenté ne correspond plus à l'épingle enregistrée. */
    CERT_CHANGED,

    /** L'appareil exige un mot de passe et aucun n'est enregistré. */
    PASSWORD_REQUIRED,

    /** Le mot de passe a été refusé par l'appareil. */
    PASSWORD_WRONG,

    /** Mot de passe enregistré mais jamais encore accepté par l'appareil. */
    PASSWORD_UNVERIFIED,

    /** L'appareil a répondu « trop d'essais » : pause temporaire. */
    THROTTLED,
}

/**
 * Registre en mémoire de l'accès aux appareils : épingles de certificat, mots de passe, refus,
 * temporisations, sessions digest, et problèmes d'accès constatés par adresse. Les épingles et
 * les mots de passe enregistrés viennent de leurs sources respectives, que le registre seul lit
 * ([ensureLoaded] avant le premier appel, puis [reload] après chaque écriture ou changement de la
 * liste), les deux sous le même verrou : si l'une des lectures échoue, l'état précédent est conservé.
 * Les valeurs provisoires ne vivent que le temps d'un essai. Les mots de passe ne sont tenus qu'EN
 * MÉMOIRE : jamais persistés par le registre (seul l'état accepté/refusé est reporté à la source),
 * jamais journalisés.
 */
@Singleton
class DeviceAccessRegistry @Inject constructor(
    private val savedPins: SavedPinSource,
    private val savedPasswords: SavedPasswordSource = SavedPasswordSource.NONE,
) {
    @Volatile private var saved: Map<String, String> = emptyMap()

    /** Vrai dès qu'une lecture des épingles enregistrées a réussi. */
    @Volatile private var loaded = false

    /**
     * Sérialise TOUTES les lectures (lecture en base + affectation sous le même verrou) : une
     * lecture ancienne et lente ne peut jamais écraser une plus récente, et une relecture lancée
     * après une écriture validée la voit forcément (revue finale C1, F1).
     */
    private val loadMutex = Mutex()
    private val provisional = ConcurrentHashMap<String, String>()

    /** Mots de passe enregistrés relus (copie en mémoire, état compris). */
    @Volatile private var savedPw: Map<String, StoredPassword> = emptyMap()
    private val provisionalPw = ConcurrentHashMap<String, String>()

    /** Adresses dont le mot de passe provisoire a été refusé. */
    private val provisionalRejected: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val throttledUntil = ConcurrentHashMap<String, Long>()
    private val sessions = ConcurrentHashMap<String, DigestSession>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Horloge, remplaçable en test. */
    internal var now: () -> Long = System::currentTimeMillis
    private val _issues = MutableStateFlow<Map<String, AccessIssue>>(emptyMap())

    /** Problèmes d'accès courants, par adresse. */
    val issues: StateFlow<Map<String, AccessIssue>> = _issues.asStateFlow()

    /**
     * Garantit que les épingles enregistrées sont connues avant tout appel réseau : au premier appel,
     * les lit via [SavedPinSource] (une seule fois, les appels concurrents attendent ce même
     * chargement). Renvoie `false` si la lecture échoue — rien n'est alors marqué comme chargé, un
     * nouvel essai aura lieu au prochain appel, et l'appelant doit refuser d'appeler (échec fermé :
     * sans épingles connues, un appareil épinglé partirait en HTTP clair).
     */
    suspend fun ensureLoaded(): Boolean {
        if (loaded) return true
        return loadMutex.withLock { loaded || readLocked() }
    }

    /**
     * Relit les épingles enregistrées en base et remplace celles du registre ; une adresse absente
     * est oubliée. À appeler APRÈS toute écriture validée en base (épingle, import, liste modifiée).
     * Renvoie `false` si la lecture échoue : les épingles déjà connues sont alors conservées (échec
     * fermé pour elles), et [ensureLoaded] reste à `false` tant qu'aucune lecture n'a réussi.
     */
    suspend fun reload(): Boolean = loadMutex.withLock { readLocked() }

    /** Lecture + affectation ; n'appeler que sous [loadMutex]. */
    private suspend fun readLocked(): Boolean {
        val pins = try {
            savedPins.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        val passwords = try {
            savedPasswords.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        saved = pins.toMap()
        savedPw = passwords.toMap()
        loaded = true
        syncPasswordIssues(passwords)
        return true
    }

    /**
     * Aligne les problèmes de mot de passe sur l'état relu. Ne touche QUE les problèmes de type
     * mot de passe : un CERT_CHANGED / HTTPS_REQUIRED déjà signalé n'est jamais écrasé.
     */
    private fun syncPasswordIssues(passwords: Map<String, StoredPassword>) {
        _issues.update { current ->
            val next = current.toMutableMap()
            for ((address, issue) in current) {
                if ((issue == AccessIssue.PASSWORD_UNVERIFIED || issue == AccessIssue.PASSWORD_WRONG) &&
                    address !in passwords
                ) {
                    next.remove(address)
                }
            }
            for ((address, stored) in passwords) {
                val existing = next[address]
                val wanted = when (stored.state) {
                    PasswordState.UNVERIFIED -> AccessIssue.PASSWORD_UNVERIFIED
                    PasswordState.REJECTED -> AccessIssue.PASSWORD_WRONG
                    PasswordState.ACCEPTED -> null
                }
                val isPasswordIssue = existing == null ||
                    existing == AccessIssue.PASSWORD_UNVERIFIED || existing == AccessIssue.PASSWORD_WRONG
                if (!isPasswordIssue) continue
                if (wanted == null) next.remove(address) else next[address] = wanted
            }
            if (next == current) current else next
        }
    }

    /** Épingle à utiliser pour [address] : la provisoire l'emporte sur l'enregistrée. */
    fun pinFor(address: String): String? = provisional[address] ?: saved[address]

    /** Fait confiance à [pin] pour [address] le temps d'un essai, sans l'enregistrer. */
    fun trustProvisionally(address: String, pin: String) {
        provisional[address] = pin
    }

    fun forgetProvisional(address: String) {
        provisional.remove(address)
    }

    /** Signale un problème pour [address] ; `null` l'efface. N'émet rien si inchangé. */
    fun report(address: String, issue: AccessIssue?) {
        _issues.update { current ->
            when {
                issue == null -> if (address in current) current - address else current
                current[address] == issue -> current
                else -> current + (address to issue)
            }
        }
    }

    // --- Mots de passe ---

    /** Vrai si le mot de passe en usage pour [address] a été refusé (provisoire d'abord). */
    private fun isRejected(address: String): Boolean =
        if (provisionalPw.containsKey(address)) address in provisionalRejected
        else savedPw[address]?.state == PasswordState.REJECTED

    /** Mot de passe à utiliser pour [address] : le provisoire d'abord, sinon l'enregistré ; `null` si refusé. */
    fun passwordFor(address: String): String? {
        if (isRejected(address)) return null
        return provisionalPw[address] ?: savedPw[address]?.password
    }

    /** Fait confiance à [password] pour [address] le temps d'un essai ; efface un refus provisoire. */
    fun trustPasswordProvisionally(address: String, password: String) {
        provisionalRejected.remove(address)
        provisionalPw[address] = password
    }

    fun forgetProvisionalPassword(address: String) {
        provisionalPw.remove(address)
        provisionalRejected.remove(address)
    }

    /** Raison pour laquelle aucune requête ne doit partir vers [address], ou `null`. */
    fun blockedFor(address: String): RpcFailure? = when {
        isRejected(address) -> RpcFailure.PASSWORD_WRONG
        now() < (throttledUntil[address] ?: 0L) -> RpcFailure.THROTTLED
        else -> null
    }

    /** L'appareil a accepté le mot de passe en usage : persiste `ACCEPTED` pour un mot de passe enregistré. */
    suspend fun onAuthAccepted(address: String) {
        if (provisionalPw.containsKey(address)) return
        val stored = savedPw[address] ?: return
        if (stored.state != PasswordState.ACCEPTED) {
            try {
                savedPasswords.markAccepted(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort : l'état en mémoire est tout de même mis à jour.
            }
            savedPw = savedPw + (address to stored.copy(state = PasswordState.ACCEPTED))
        }
        if (_issues.value[address] == AccessIssue.PASSWORD_UNVERIFIED) report(address, null)
    }

    /** L'appareil a refusé le mot de passe en usage : refus en mémoire, et persisté s'il est enregistré. */
    suspend fun onAuthRejected(address: String) {
        if (provisionalPw.containsKey(address)) {
            provisionalRejected.add(address)
            return
        }
        val stored = savedPw[address] ?: return
        try {
            savedPasswords.markRejected(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Le refus en mémoire s'applique malgré tout.
        }
        savedPw = savedPw + (address to stored.copy(state = PasswordState.REJECTED))
        val current = _issues.value[address]
        if (current == null || current == AccessIssue.PASSWORD_UNVERIFIED) report(address, AccessIssue.PASSWORD_WRONG)
    }

    /** HTTP 429 : aucune requête vers [address] pendant [THROTTLE_BACKOFF_MS]. */
    fun onThrottled(address: String) {
        throttledUntil[address] = now() + THROTTLE_BACKOFF_MS
    }

    // --- Sessions digest et verrous ---

    /** Session digest en cours pour la cible [target] (ex. `https://1.2.3.4`), jamais d'une autre cible. */
    fun sessionFor(target: String): DigestSession? = sessions[target]

    fun setSession(target: String, session: DigestSession?) {
        if (session == null) sessions.remove(target) else sessions[target] = session
    }

    /** Verrou propre à [address] : sérialise les appels authentifiés vers un même appareil. */
    fun lockFor(address: String): Mutex = locks.computeIfAbsent(address) { Mutex() }

    companion object {
        /** Durée de la pause après un HTTP 429. */
        const val THROTTLE_BACKOFF_MS = 60_000L
    }
}
