package kapoue.hestia.data.rpc

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

// Primitives d'épinglage de certificat pour les appareils en HTTPS seul.
//
// Le firmware 2.0+ de certains appareils impose HTTPS, avec un certificat autosigné qu'aucune
// autorité ne peut valider. Hestia l'épingle donc au premier contact (TOFU) : la clé publique vue
// à ce moment-là est mémorisée, puis toute connexion ultérieure doit présenter exactement la même.
// Jamais d'acceptation aveugle : une clé différente de celle épinglée est toujours refusée.

/** Certificat observé lors d'un premier contact : empreinte d'épinglage et empreinte affichable. */
data class PeekedCertificate(val pin: String, val fingerprint: String)

object CertPins {
    /** Épingle au format OkHttp : `sha256/` + Base64 du SHA-256 de la clé publique encodée. */
    fun pinOf(cert: X509Certificate): String =
        "sha256/" + Base64.getEncoder().encodeToString(sha256(cert.publicKey.encoded))

    /** Empreinte du certificat entier, en hexa majuscule séparé par `:` (à montrer à l'utilisateur). */
    fun fingerprintOf(cert: X509Certificate): String =
        sha256(cert.encoded).joinToString(":") { "%02X".format(it) }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** La clé présentée par l'appareil ne correspond pas à l'épingle mémorisée. */
class PinMismatchException(message: String) : CertificateException(message)

/** TrustManager qui n'accepte qu'une seule clé publique, celle de l'épingle attendue. */
class PinnedTrustManager(private val expectedPin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
        if (CertPins.pinOf(chain[0]) != expectedPin) throw PinMismatchException("Certificate pin mismatch")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Hôte d'une adresse, éventuellement suivie d'un port (`127.0.0.1:8443`, tests uniquement). */
internal fun hostOf(address: String): String = address.substringBefore(':')

/** Port d'une adresse, ou [default] si elle n'en porte pas. */
internal fun portOf(address: String, default: Int): Int =
    address.substringAfter(':', "").toIntOrNull() ?: default
