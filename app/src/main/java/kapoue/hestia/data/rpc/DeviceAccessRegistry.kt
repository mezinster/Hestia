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

    /** Adresses dont le mot de passe provisoire EN COURS a été accepté par l'appareil. */
    private val provisionalAccepted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val throttledUntil = ConcurrentHashMap<String, Long>()
    private val sessions = ConcurrentHashMap<String, DigestSession>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Adresses ayant déjà redirigé vers HTTPS pendant ce processus. Collant : une réponse HTTP non
     * authentifiée qui efface le problème signalé ne doit pas rouvrir l'envoi d'identifiants en
     * clair. Jamais vidé en cours de processus (une adresse épinglée passe de toute façon en HTTPS).
     * Résiduel accepté (spec § 6.2) : après redémarrage de l'appli, un appareil non épinglé peut de
     * nouveau recevoir du digest en HTTP, comme tout appareil protégé non épinglé.
     */
    private val httpsSeenAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Horloge, remplaçable en test. */
    internal var now: () -> Long = System::currentTimeMillis
    private val _issues = MutableStateFlow<Map<String, AccessIssue>>(emptyMap())

    /** Problèmes d'accès courants, par adresse. */
    val issues: StateFlow<Map<String, AccessIssue>> = _issues.asStateFlow()

    private val _passwordAddresses = MutableStateFlow<Set<String>>(emptySet())

    /** Adresses ayant un mot de passe enregistré (les mots de passe provisoires n'y figurent pas). */
    val passwordAddresses: StateFlow<Set<String>> = _passwordAddresses.asStateFlow()

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
        _passwordAddresses.value = passwords.keys.toSet()
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

    /** Note que [address] a exigé le HTTPS (voir [httpsSeenAddresses]). */
    fun markHttpsSeen(address: String) {
        httpsSeenAddresses.add(address)
    }

    /** Vrai si [address] a exigé le HTTPS depuis le lancement : plus d'identifiants en HTTP clair. */
    fun httpsSeen(address: String): Boolean = address in httpsSeenAddresses

    /**
     * Oublie que [address] a exigé le HTTPS (certificat oublié par l'utilisateur). À n'appeler
     * qu'une fois le mot de passe retiré : sinon un défi en HTTP clair recevrait un digest.
     */
    fun clearHttpsSeen(address: String) {
        httpsSeenAddresses.remove(address)
    }

    /** Signale un problème pour [address] ; `null` l'efface. N'émet rien si inchangé. */
    fun report(address: String, issue: AccessIssue?) {
        if (issue == AccessIssue.HTTPS_REQUIRED) markHttpsSeen(address)
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

    /** Mot de passe provisoire seul (jamais l'enregistré) ; `null` si aucun. */
    fun provisionalPasswordFor(address: String): String? = provisionalPw[address]

    /**
     * Fait confiance à [password] pour [address] le temps d'un essai ; efface un refus provisoire et
     * les sessions digest de l'adresse (un nouveau mot de passe ne doit pas d'abord partir sur
     * l'ancien nonce : ce serait un essai raté de plus pour l'anti-brute-force).
     */
    fun trustPasswordProvisionally(address: String, password: String) {
        provisionalRejected.remove(address)
        provisionalAccepted.remove(address)
        provisionalPw[address] = password
        clearSessions(address)
    }

    fun forgetProvisionalPassword(address: String) {
        provisionalPw.remove(address)
        provisionalRejected.remove(address)
        provisionalAccepted.remove(address)
        clearSessions(address)
    }

    /** Vrai si l'appareil a accepté le mot de passe provisoire en cours pour [address]. */
    fun provisionalPasswordAccepted(address: String): Boolean =
        provisionalPw.containsKey(address) && address in provisionalAccepted

    /** Raison pour laquelle aucune requête ne doit partir vers [address], ou `null`. */
    fun blockedFor(address: String): RpcFailure? = when {
        isRejected(address) -> RpcFailure.PASSWORD_WRONG
        now() < (throttledUntil[address] ?: 0L) -> RpcFailure.THROTTLED
        else -> null
    }

    /**
     * L'appareil a accepté [passwordUsed] (celui réellement envoyé) pour [address]. Persiste
     * `ACCEPTED` pour un mot de passe enregistré ; un résultat périmé (mot de passe remplacé
     * depuis l'envoi) est ignoré. Tout se déroule sous [loadMutex].
     */
    suspend fun onAuthAccepted(address: String, passwordUsed: String) = loadMutex.withLock {
        if (provisionalPw.containsKey(address)) {
            // Provisoire : rien n'est persisté, l'acceptation est seulement notée pour l'ajout.
            if (provisionalPw[address] == passwordUsed) provisionalAccepted.add(address)
            return@withLock
        }
        val stored = savedPw[address] ?: return@withLock
        if (stored.password != passwordUsed) return@withLock
        if (stored.state != PasswordState.ACCEPTED) {
            applyMark(address, stored, PasswordState.ACCEPTED) { savedPasswords.markAccepted(address, passwordUsed) }
        }
        if (_issues.value[address] == AccessIssue.PASSWORD_UNVERIFIED) report(address, null)
    }

    /**
     * L'appareil a refusé [passwordUsed] pour [address] : refus en mémoire, et persisté s'il est
     * enregistré. Un résultat périmé est ignoré. Tout se déroule sous [loadMutex].
     */
    suspend fun onAuthRejected(address: String, passwordUsed: String) = loadMutex.withLock {
        if (provisionalPw.containsKey(address)) {
            if (provisionalPw[address] == passwordUsed) provisionalRejected.add(address)
            return@withLock
        }
        val stored = savedPw[address] ?: return@withLock
        if (stored.password != passwordUsed) return@withLock
        applyMark(address, stored, PasswordState.REJECTED) { savedPasswords.markRejected(address, passwordUsed) }
        val current = _issues.value[address]
        if (current == null || current == AccessIssue.PASSWORD_UNVERIFIED ||
            current == AccessIssue.PASSWORD_REQUIRED || current == AccessIssue.THROTTLED
        ) {
            report(address, AccessIssue.PASSWORD_WRONG)
        }
    }

    /**
     * Persiste via [write] (échec avalé) puis relit tout (couvre les alias d'adresse) ; si l'écriture
     * ou la relecture échoue, applique l'état à [address] seule en mémoire. À appeler sous [loadMutex].
     */
    private suspend fun applyMark(
        address: String,
        stored: StoredPassword,
        state: PasswordState,
        write: suspend () -> Unit,
    ) {
        var written = true
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort : l'état en mémoire est tout de même mis à jour ci-dessous.
            written = false
        }
        if (!written || !readLocked()) {
            savedPw = savedPw + (address to stored.copy(state = state))
        }
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

    /** Efface les sessions digest des deux cibles de [address] (`http://` et `https://`). */
    fun clearSessions(address: String) {
        sessions.remove("http://$address")
        sessions.remove("https://$address")
    }

    /** Verrou propre à [address] : sérialise les appels authentifiés vers un même appareil. */
    fun lockFor(address: String): Mutex = locks.computeIfAbsent(address) { Mutex() }

    companion object {
        /** Durée de la pause après un HTTP 429. */
        const val THROTTLE_BACKOFF_MS = 60_000L
    }
}
