package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.LightSetResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Appels RPC du composant `Light` (variateurs, 2026-10-07) — passe par [ShellyRpcClient.call] pour
 * hériter des timeouts, de la traduction des erreurs et du journal de diagnostic.
 */
@Singleton
class LightRpcClient @Inject constructor(
    private val rpc: ShellyRpcClient,
) {
    suspend fun getLightStatus(ip: String, id: Int): RpcResult<LightStatusResult> =
        rpc.call(ip, "Light.GetStatus", buildJsonObject { put("id", id) }, LightStatusResult.serializer())

    /** Au moins un de [on] / [brightness] (0–100). Allumer sans luminosité garde la dernière valeur. */
    suspend fun setLight(ip: String, id: Int, on: Boolean?, brightness: Int?): RpcResult<LightSetResult> {
        require(on != null || brightness != null) { "Light.Set exige on ou brightness" }
        return rpc.call(
            ip,
            "Light.Set",
            buildJsonObject {
                put("id", id)
                on?.let { put("on", it) }
                brightness?.let { put("brightness", it) }
            },
            LightSetResult.serializer(),
        )
    }
}
