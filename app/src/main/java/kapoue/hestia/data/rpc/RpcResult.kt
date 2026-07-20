package kapoue.hestia.data.rpc

/**
 * Résultat d'un appel RPC. La couche data ne produit **aucune chaîne destinée à l'utilisateur** :
 * elle renvoie un type d'erreur que l'UI traduit via strings.xml (messages actionnables).
 */
sealed interface RpcResult<out T> {
    data class Success<T>(val value: T) : RpcResult<T>

    /** Erreur applicative renvoyée par l'appareil (code JSON-RPC négatif). */
    data class RpcError(val code: Int, val message: String) : RpcResult<Nothing>

    /** Échec au niveau réseau/transport, sans réponse exploitable de l'appareil. */
    data class Failure(val kind: RpcFailure) : RpcResult<Nothing>
}

/** Catégories d'échec réseau, mappées à un message utilisateur par l'UI. */
enum class RpcFailure {
    TIMEOUT,
    UNREACHABLE,
    MALFORMED_RESPONSE,
    NOT_SHELLY_GEN2,
    GEN1_UNSUPPORTED,
}

/** Exécute [block] uniquement si le résultat est un succès. */
inline fun <T> RpcResult<T>.onSuccess(block: (T) -> Unit): RpcResult<T> {
    if (this is RpcResult.Success) block(value)
    return this
}

/** Valeur en cas de succès, sinon null. */
fun <T> RpcResult<T>.getOrNull(): T? = (this as? RpcResult.Success)?.value

/** Variante d'erreur (RpcError ou Failure) si présente, sinon null — pour court-circuiter une séquence. */
fun RpcResult<*>.errorOrNull(): RpcResult<Nothing>? = when (this) {
    is RpcResult.Success -> null
    is RpcResult.RpcError -> this
    is RpcResult.Failure -> this
}
