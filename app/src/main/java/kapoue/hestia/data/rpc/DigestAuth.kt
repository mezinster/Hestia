package kapoue.hestia.data.rpc

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** Défi « WWW-Authenticate: Digest » renvoyé par l'appareil. */
data class Challenge(val realm: String, val nonce: String)

/**
 * Authentification Digest SHA-256 (RFC 7616, qop=auth) pour l'API RPC locale.
 * Pur Kotlin, sans réseau : la construction de l'en-tête est testable isolément.
 * Le mot de passe n'apparaît jamais dans l'en-tête produit, seulement son empreinte.
 */
object DigestAuth {
    const val USERNAME = "admin"

    /** Méthode et URI fixes : l'API RPC Shelly est appelée en POST /rpc. */
    private const val URI = "/rpc"

    private val random = SecureRandom()

    /** Extrait realm et nonce d'un en-tête Digest ; null si absent, non Digest ou incomplet. */
    fun parseChallenge(header: String?): Challenge? {
        if (header == null) return null
        val trimmed = header.trimStart()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        val params = PARAM_REGEX.findAll(trimmed.substring("Digest".length)).associate {
            it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] })
        }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return Challenge(realm, nonce)
    }

    /** Construit la valeur de l'en-tête Authorization pour un appel POST /rpc. */
    fun authorizationHeader(
        realm: String,
        nonce: String,
        nc: Int,
        cnonce: String,
        password: String,
    ): String {
        val ha1 = sha256Hex("$USERNAME:$realm:$password")
        val ha2 = sha256Hex("POST:$URI")
        val ncHex = String.format("%08x", nc)
        val response = sha256Hex("$ha1:$nonce:$ncHex:$cnonce:auth:$ha2")
        return "Digest username=\"$USERNAME\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$URI\", " +
            "algorithm=SHA-256, response=\"$response\", qop=auth, nc=$ncHex, cnonce=\"$cnonce\""
    }

    /** Nonce client : 8 octets aléatoires en hexadécimal. */
    fun newCnonce(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** key="valeur quotée" ou key=valeur nue ; la clé est insensible à la casse. */
    private val PARAM_REGEX = Regex("""([A-Za-z]+)\s*=\s*(?:"([^"]*)"|([^,\s]*))""")
}

/** Compteur nc par défi : commence à 1, incrément thread-safe. */
class DigestSession(val realm: String, val nonce: String) {
    private val counter = AtomicInteger(0)

    fun nextNc(): Int = counter.incrementAndGet()
}
