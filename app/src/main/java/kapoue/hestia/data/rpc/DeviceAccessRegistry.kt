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
}

/**
 * Registre en mémoire des épingles de certificat par adresse d'appareil et des problèmes d'accès
 * constatés. Rien n'est persisté ici : les épingles enregistrées viennent de la base, et c'est le
 * registre seul qui les y lit ([ensureLoaded] avant le premier appel, puis [reload] après chaque
 * écriture ou changement de la liste). Les provisoires ne vivent que le temps d'un essai. Aucune
 * donnée sensible (pas de mot de passe) n'est conservée.
 */
@Singleton
class DeviceAccessRegistry @Inject constructor(private val savedPins: SavedPinSource) {
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
        saved = pins.toMap()
        loaded = true
        return true
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
}
