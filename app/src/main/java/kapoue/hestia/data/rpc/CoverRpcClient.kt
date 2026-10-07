package kapoue.hestia.data.rpc

import kapoue.hestia.data.rpc.model.CoverConfigResult
import kapoue.hestia.data.rpc.model.CoverStatusResult
import kapoue.hestia.data.rpc.model.SetConfigResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Appels RPC du composant `Cover` (volets roulants, 2026-10-07) — passe par [ShellyRpcClient.call]
 * pour hériter des timeouts, de la traduction des erreurs et du journal de diagnostic. Les actions
 * renvoient un objet vide, accepté tel quel par [SetConfigResult].
 */
@Singleton
class CoverRpcClient @Inject constructor(
    private val rpc: ShellyRpcClient,
) {
    suspend fun getStatus(ip: String, id: Int): RpcResult<CoverStatusResult> =
        rpc.call(ip, "Cover.GetStatus", coverIdParams(id), CoverStatusResult.serializer())

    suspend fun open(ip: String, id: Int): RpcResult<SetConfigResult> =
        rpc.call(ip, "Cover.Open", coverIdParams(id), SetConfigResult.serializer())

    suspend fun close(ip: String, id: Int): RpcResult<SetConfigResult> =
        rpc.call(ip, "Cover.Close", coverIdParams(id), SetConfigResult.serializer())

    suspend fun stop(ip: String, id: Int): RpcResult<SetConfigResult> =
        rpc.call(ip, "Cover.Stop", coverIdParams(id), SetConfigResult.serializer())

    /** Va à [pos] (0 fermé – 100 ouvert, borné) ; exige un volet calibré. */
    suspend fun goToPosition(ip: String, id: Int, pos: Int): RpcResult<SetConfigResult> =
        rpc.call(ip, "Cover.GoToPosition", coverGoToParams(id, pos), SetConfigResult.serializer())

    /** Lance la calibration, tenue par l'appareil lui-même. */
    suspend fun calibrate(ip: String, id: Int): RpcResult<SetConfigResult> =
        rpc.call(ip, "Cover.Calibrate", coverIdParams(id), SetConfigResult.serializer())

    /** Nom du canal tel que configuré sur l'appareil (Cover.GetConfig.name). */
    suspend fun getConfig(ip: String, id: Int): RpcResult<CoverConfigResult> =
        rpc.call(ip, "Cover.GetConfig", coverIdParams(id), CoverConfigResult.serializer())

    /** Écrit le nom du canal sur l'appareil (Cover.SetConfig.name), comme pour un relais. */
    suspend fun setConfigName(ip: String, id: Int, name: String): RpcResult<SetConfigResult> =
        rpc.call(ip, "Cover.SetConfig", coverSetConfigNameParams(id, name), SetConfigResult.serializer())
}

/** Paramètres des appels Cover ne portant que l'identifiant du canal. */
internal fun coverIdParams(id: Int): JsonObject = buildJsonObject { put("id", id) }

/** Paramètres de Cover.GoToPosition : position bornée à 0–100. */
internal fun coverGoToParams(id: Int, pos: Int): JsonObject = buildJsonObject {
    put("id", id)
    put("pos", pos.coerceIn(0, 100))
}

/** Paramètres de Cover.SetConfig pour le seul nom du canal. */
internal fun coverSetConfigNameParams(id: Int, name: String): JsonObject = buildJsonObject {
    put("id", id)
    put("config", buildJsonObject { put("name", name) })
}
