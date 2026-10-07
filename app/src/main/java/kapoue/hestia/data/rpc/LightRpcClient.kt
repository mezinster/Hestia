package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.LightConfigResult
import kapoue.hestia.data.rpc.model.LightSetResult
import kapoue.hestia.data.rpc.model.LightStatusResult
import kapoue.hestia.data.rpc.model.SetConfigResult
import kotlinx.serialization.json.JsonObject
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

    /**
     * Au moins un de [on] / [brightness] (0–100). Allumer sans luminosité garde la dernière valeur.
     * [toggleAfterSec] : minuteur tenu par l'appareil lui-même (rebascule après ce délai, même
     * téléphone éteint), comme `Switch.Set` pour un relais.
     */
    suspend fun setLight(ip: String, id: Int, on: Boolean?, brightness: Int?, toggleAfterSec: Int? = null): RpcResult<LightSetResult> {
        require(on != null || brightness != null) { "Light.Set exige on ou brightness" }
        return rpc.call(ip, "Light.Set", lightSetParams(id, on, brightness, toggleAfterSec), LightSetResult.serializer())
    }

    /** Nom du canal tel que configuré sur l'appareil (Light.GetConfig.name). */
    suspend fun getConfig(ip: String, id: Int): RpcResult<LightConfigResult> =
        rpc.call(ip, "Light.GetConfig", buildJsonObject { put("id", id) }, LightConfigResult.serializer())

    /** Écrit le nom du canal sur l'appareil (Light.SetConfig.name), comme pour un relais. */
    suspend fun setConfigName(ip: String, id: Int, name: String): RpcResult<SetConfigResult> =
        rpc.call(ip, "Light.SetConfig", lightSetConfigNameParams(id, name), SetConfigResult.serializer())
}

/** Paramètres de Light.Set : seuls les champs fournis sont envoyés. */
internal fun lightSetParams(id: Int, on: Boolean?, brightness: Int?, toggleAfterSec: Int?): JsonObject = buildJsonObject {
    put("id", id)
    on?.let { put("on", it) }
    brightness?.let { put("brightness", it) }
    toggleAfterSec?.let { put("toggle_after", it) }
}

/** Paramètres de Light.SetConfig pour le seul nom du canal. */
internal fun lightSetConfigNameParams(id: Int, name: String): JsonObject = buildJsonObject {
    put("id", id)
    put("config", buildJsonObject { put("name", name) })
}
