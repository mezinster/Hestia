package kapoue.hestia.data.rpc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * constatés. Rien n'est persisté ici : les épingles enregistrées viennent de la base (via
 * [syncSavedPins]) et les provisoires ne vivent que le temps d'un essai. Aucune donnée sensible
 * (pas de mot de passe) n'est conservée.
 */
@Singleton
class DeviceAccessRegistry @Inject constructor() {
    @Volatile private var saved: Map<String, String> = emptyMap()
    private val provisional = ConcurrentHashMap<String, String>()
    private val _issues = MutableStateFlow<Map<String, AccessIssue>>(emptyMap())

    /** Problèmes d'accès courants, par adresse. */
    val issues: StateFlow<Map<String, AccessIssue>> = _issues.asStateFlow()

    /** Épingle à utiliser pour [address] : la provisoire l'emporte sur l'enregistrée. */
    fun pinFor(address: String): String? = provisional[address] ?: saved[address]

    /** Remplace toutes les épingles enregistrées ; une adresse absente est oubliée. */
    fun syncSavedPins(pinsByAddress: Map<String, String>) {
        saved = pinsByAddress.toMap()
    }

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
