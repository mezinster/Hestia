package kapoue.hestia.ui.common

import androidx.annotation.StringRes
import kapoue.hestia.R
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult

/**
 * Message destiné à l'utilisateur, porté sous forme de ressource (+ argument optionnel) pour
 * garder la couche présentation libre de toute chaîne en dur et de tout Context dans les VM.
 */
data class UserMessage(
    @StringRes val res: Int,
    val arg: Int? = null,
)

/** Traduit un échec RPC en message actionnable (SPEC-V1 § 3 « Erreurs et robustesse »). */
fun RpcResult<*>.toUserMessageOrNull(): UserMessage? = when (this) {
    is RpcResult.Success -> null
    is RpcResult.RpcError -> UserMessage(R.string.error_rpc_generic, code)
    is RpcResult.Failure -> UserMessage(
        when (kind) {
            RpcFailure.TIMEOUT -> R.string.error_timeout
            RpcFailure.UNREACHABLE -> R.string.error_unreachable
            RpcFailure.MALFORMED_RESPONSE -> R.string.error_not_shelly
            RpcFailure.NOT_SHELLY_GEN2 -> R.string.error_not_shelly
            RpcFailure.GEN1_UNSUPPORTED -> R.string.error_gen1_unsupported
        },
    )
}
