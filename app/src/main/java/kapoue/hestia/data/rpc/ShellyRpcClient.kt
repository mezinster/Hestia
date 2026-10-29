package kapoue.hestia.data.rpc

import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.rpc.model.CheckForUpdateResult
import kapoue.hestia.data.rpc.model.CloudConfigResult
import kapoue.hestia.data.rpc.model.CloudStatusResult
import kapoue.hestia.data.rpc.model.ComponentsResult
import kapoue.hestia.data.rpc.model.DeviceInfoResult
import kapoue.hestia.data.rpc.model.LedUiConfigResult
import kapoue.hestia.data.rpc.model.RpcEnvelope
import kapoue.hestia.data.rpc.model.RpcRequest
import kapoue.hestia.data.rpc.model.ScheduleCreateResult
import kapoue.hestia.data.rpc.model.ScheduleDeleteResult
import kapoue.hestia.data.rpc.model.ScheduleListResult
import kapoue.hestia.data.rpc.model.ScriptCreateResult
import kapoue.hestia.data.rpc.model.ScriptEvalResult
import kapoue.hestia.data.rpc.model.ScriptGetCodeResult
import kapoue.hestia.data.rpc.model.ScriptListResult
import kapoue.hestia.data.rpc.model.ScriptPutCodeResult
import kapoue.hestia.data.rpc.model.ScriptRunResult
import kapoue.hestia.data.rpc.model.SetConfigResult
import kapoue.hestia.data.rpc.model.ShellyFullStatus
import kapoue.hestia.data.rpc.model.SwitchConfigResult
import kapoue.hestia.data.rpc.model.SwitchSetResult
import kapoue.hestia.data.rpc.model.SwitchStatusResult
import kapoue.hestia.data.rpc.model.WebhookCreateResult
import kapoue.hestia.data.rpc.model.WebhookListResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Client JSON-RPC 2.0 pour les appareils Shelly Gen2+ (SPEC-V1 § 3).
 *
 * Principes :
 * - communication HTTP locale uniquement, vers l'adresse fournie par l'appelant ;
 * - timeout court, **aucun retour automatique** (le réessai est déclenché par l'utilisateur) ;
 * - aucune chaîne utilisateur produite ici : les erreurs sont typées ([RpcResult]) ;
 * - chaque appel est journalisé (méthode, cible, code, durée).
 */
@Singleton
class ShellyRpcClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val logger: DiagnosticLogger,
    private val access: DeviceAccessRegistry,
) {
    private val requestIds = java.util.concurrent.atomic.AtomicInteger(1)
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Client local qui ne suit **jamais** une redirection : une redirection vers HTTPS signale un
     * appareil en HTTPS seul (firmware 2.0+), à faire confirmer par l'utilisateur ; la suivre
     * reviendrait à accepter un certificat non épinglé, ou à envoyer la requête ailleurs.
     */
    private val localClient: OkHttpClient by lazy {
        httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /** Clients HTTPS épinglés, un par couple adresse + épingle (`"$ip#$pin"`). */
    private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * Client HTTPS qui n'accepte que la clé publique [pin] pour [ip]. Le nom d'hôte attendu est
     * l'adresse IP saisie : le certificat autosigné de l'appareil ne porte de toute façon aucun
     * nom vérifiable, c'est l'épingle qui fait foi.
     */
    private fun pinnedClient(ip: String, pin: String): OkHttpClient =
        pinnedClients.getOrPut("$ip#$pin") {
            val trustManager = PinnedTrustManager(pin)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            val expectedHost = hostOf(ip)
            localClient.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { host, _ -> host == expectedHost }
                .build()
        }

    /**
     * Lit le certificat présenté par l'appareil **sans lui faire confiance** : la poignée de main
     * TLS est interrompue dès le certificat capturé, aucune requête HTTP n'est envoyée. Sert à
     * montrer l'empreinte à l'utilisateur avant qu'il décide de l'épingler (TOFU explicite).
     */
    suspend fun peekCertificate(ip: String): RpcResult<PeekedCertificate> = withContext(Dispatchers.IO) {
        if (ip.startsWith(DeviceRepository.DEMO_IP_PREFIX)) {
            logger.info(DiagnosticLogger.RPC, "TLS @ $ip → appareil démo, aucun appel réseau")
            return@withContext RpcResult.Failure(RpcFailure.UNREACHABLE)
        }
        var captured: X509Certificate? = null
        // Mémorise le certificat de tête puis refuse toujours : la session n'aboutit jamais.
        val capturing = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                captured = chain?.firstOrNull()
                throw CertificateException("Certificate captured, never trusted")
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not supported")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val startedAt = System.currentTimeMillis()
        var failure: String? = null
        try {
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capturing), SecureRandom()) }
            (sslContext.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = PEEK_TIMEOUT_MS
                socket.connect(InetSocketAddress(hostOf(ip), portOf(ip, HTTPS_PORT)), PEEK_TIMEOUT_MS)
                socket.startHandshake()
            }
        } catch (e: IOException) {
            // Attendu une fois le certificat capturé : le trust manager interrompt toujours la
            // poignée de main. Sans capture, c'est un vrai échec (port fermé, pas de TLS…).
            failure = e.javaClass.simpleName
        } catch (e: SecurityException) {
            // Accès au réseau local refusé (Android 17, ACCESS_LOCAL_NETWORK) : peut remonter ainsi
            // plutôt qu'en IOException. Échec propre, jamais de plantage dans viewModelScope.
            failure = "accès réseau local refusé"
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val cert = captured
        if (cert == null) {
            logger.warn(DiagnosticLogger.RPC, "TLS @ $ip → certificat illisible : ${failure ?: "aucun"} (${elapsed}ms)")
            return@withContext RpcResult.Failure(RpcFailure.UNREACHABLE)
        }
        val peeked = PeekedCertificate(pin = CertPins.pinOf(cert), fingerprint = CertPins.fingerprintOf(cert))
        logger.info(
            DiagnosticLogger.RPC,
            "TLS @ $ip → certificat lu, empreinte ${peeked.fingerprint.take(LOGGED_FINGERPRINT_CHARS)}… (${elapsed}ms)",
        )
        RpcResult.Success(peeked)
    }

    /** État complet d'un canal switch. */
    suspend fun getSwitchStatus(ip: String, switchId: Int): RpcResult<SwitchStatusResult> =
        call(ip, "Switch.GetStatus", buildJsonObject { put("id", switchId) }, SwitchStatusResult.serializer())

    /**
     * Nom du canal tel que configuré sur l'appareil (Switch.GetConfig.name), s'il existe. Appel
     * ciblé sur un seul canal (léger, comparable à [getSwitchStatus]) — pour resynchroniser le
     * nom d'un canal déjà connu de Hestia, sans repasser par le plus coûteux Shelly.GetComponents
     * (réservé à [probe], à l'ajout). Voir SPEC nom des prises, 2026-09-07.
     */
    suspend fun getSwitchConfig(ip: String, switchId: Int): RpcResult<SwitchConfigResult> =
        call(ip, "Switch.GetConfig", buildJsonObject { put("id", switchId) }, SwitchConfigResult.serializer())

    /**
     * Écrit le nom d'un canal sur l'appareil (Switch.SetConfig.name) — purement cosmétique,
     * aucun effet sur le comportement du relais. Symétrique de [getSwitchConfig]. Nom des prises,
     * 2026-09-07.
     */
    suspend fun switchSetConfigName(ip: String, switchId: Int, name: String): RpcResult<SetConfigResult> = call(
        ip,
        "Switch.SetConfig",
        buildJsonObject {
            put("id", switchId)
            put("config", buildJsonObject { put("name", name) })
        },
        SetConfigResult.serializer(),
    )

    /**
     * Écrit le nom de l'appareil physique (Sys.SetConfig.device.name) — utilisé pour l'en-tête
     * d'un bloc multi-canaux et pour les détecteurs de fumée (mono-canal, sans composant Switch).
     * Même nom que celui visible depuis l'appli Shelly officielle ou le cloud. Nom des prises,
     * 2026-09-07.
     */
    suspend fun sysSetConfigName(ip: String, name: String): RpcResult<SetConfigResult> = call(
        ip,
        "Sys.SetConfig",
        buildJsonObject {
            put("config", buildJsonObject { put("device", buildJsonObject { put("name", name) }) })
        },
        SetConfigResult.serializer(),
    )

    /**
     * Allume ou éteint un canal switch.
     *
     * [toggleAfterSec] arme un minuteur **one-shot** tenu par l'appareil : il rebascule le canal
     * après ce délai, puis oublie. C'est un compte à rebours volatil, il n'écrit **rien** dans la
     * configuration de l'appareil — contrairement à `auto_off`, qui est une configuration
     * persistante et s'appliquerait dès lors à *tous* les allumages suivants, y compris ceux
     * déclenchés par le bouton physique ou l'interface web native.
     *
     * L'appareil renseigne quand même `timer_started_at` / `timer_duration` dans Switch.GetStatus,
     * donc le compte à rebours reste lisible exactement comme avec auto_off.
     */
    suspend fun setSwitch(
        ip: String,
        switchId: Int,
        on: Boolean,
        toggleAfterSec: Int? = null,
    ): RpcResult<SwitchSetResult> =
        call(
            ip,
            "Switch.Set",
            buildJsonObject {
                put("id", switchId)
                put("on", on)
                if (toggleAfterSec != null) put("toggle_after", toggleAfterSec)
            },
            SwitchSetResult.serializer(),
        )

    /**
     * Désarme le minuteur `auto_off` d'un canal.
     *
     * Hestia n'arme **jamais** `auto_off` : ses minuteurs passent par `toggle_after` (voir
     * [setSwitch]). Cet appel ne sert qu'à neutraliser une configuration posée **hors** de
     * l'application (interface web native, version antérieure), qui entrerait en conflit avec la
     * simulation de présence. Il n'existe volontairement aucun moyen d'armer `auto_off` depuis
     * ce client : c'est ce qui a causé le bug « l'interrupteur lance un minuteur ».
     */
    suspend fun clearAutoOff(ip: String, switchId: Int): RpcResult<SetConfigResult> = call(
        ip,
        "Switch.SetConfig",
        buildJsonObject {
            put("id", switchId)
            put(
                "config",
                buildJsonObject {
                    put("auto_off", false)
                },
            )
        },
        SetConfigResult.serializer(),
    )

    /** Infos appareil : modèle, génération, firmware. */
    suspend fun getDeviceInfo(ip: String): RpcResult<DeviceInfoResult> =
        call(ip, "Shelly.GetDeviceInfo", null, DeviceInfoResult.serializer())

    /**
     * Interroge les serveurs Shelly pour savoir si une mise à jour de firmware est disponible.
     * Seul appel RPC du projet qui fait sortir l'appareil du réseau local — **exclusivement à la
     * demande explicite de l'utilisateur** (bouton « Vérifier »), jamais en tâche de fond.
     */
    suspend fun checkForUpdate(ip: String): RpcResult<CheckForUpdateResult> =
        call(ip, "Shelly.CheckForUpdate", null, CheckForUpdateResult.serializer())

    /** Installe la mise à jour du canal [stage] ; l'appareil redémarre une fois l'installation faite. */
    suspend fun updateFirmware(ip: String, stage: String = "stable"): RpcResult<SetConfigResult> =
        call(ip, "Shelly.Update", buildJsonObject { put("stage", stage) }, SetConfigResult.serializer())

    /** Redémarre l'appareil (dépannage). Toujours à la demande explicite, jamais automatique. */
    suspend fun reboot(ip: String): RpcResult<SetConfigResult> =
        call(ip, "Shelly.Reboot", null, SetConfigResult.serializer())

    /**
     * Coupe l'alarme sonore d'un détecteur de fumée en cours (voir SMOKE-DETECTOR.md). Ne touche
     * pas à la détection elle-même : si de la fumée est toujours présente, l'appareil réarmera
     * l'alarme de lui-même (comportement natif, jamais Hestia). Pas de `Smoke.Test` : confirmé
     * absent de l'API RPC officielle (recherché le 2026-08-31) — le test ne se déclenche que
     * physiquement, par appui sur le bouton de l'appareil.
     */
    suspend fun muteSmoke(ip: String, id: Int): RpcResult<SetConfigResult> =
        call(ip, "Smoke.Mute", buildJsonObject { put("id", id) }, SetConfigResult.serializer())

    /** État complet de l'appareil ; on n'exploite que la section `sys` (horloge). */
    suspend fun getFullStatus(ip: String): RpcResult<ShellyFullStatus> =
        call(ip, "Shelly.GetStatus", null, ShellyFullStatus.serializer())

    // --- LED d'état (composant `plugs_ui`/`powerstrip_ui`, nom et casse variables selon le modèle) ---

    /** [component] = nom exact du composant (ex. `plugs_ui`, `POWERSTRIP_UI`) — voir [DeviceRepository]. */
    suspend fun ledUiGetConfig(ip: String, component: String): RpcResult<LedUiConfigResult> =
        call(ip, "$component.GetConfig", null, LedUiConfigResult.serializer())

    /**
     * Réécrit entièrement `night_mode` (jamais de mise à jour partielle) : [brightness] (0-100) et
     * [activeBetween] (`["HH:MM","HH:MM"]`) couvrent aussi bien le mode « allumée, réduite la nuit »
     * (30 %, 22h-8h) que « éteinte en permanence » (0 %, toute la journée) — un seul mécanisme
     * natif réutilisé pour les deux, validé en direct le 2026-08-15.
     */
    suspend fun ledUiSetConfig(
        ip: String,
        component: String,
        brightness: Int,
        activeBetween: List<String>,
    ): RpcResult<SetConfigResult> = call(
        ip,
        "$component.SetConfig",
        buildJsonObject {
            put(
                "config",
                buildJsonObject {
                    put(
                        "leds",
                        buildJsonObject {
                            put(
                                "night_mode",
                                buildJsonObject {
                                    put("enable", true)
                                    put("brightness", brightness)
                                    put("active_between", buildJsonArray { activeBetween.forEach { add(it) } })
                                },
                            )
                        },
                    )
                },
            )
        },
        SetConfigResult.serializer(),
    )

    // --- Cloud Shelly (opt-in, désactivé par défaut — voir CLAUDE.md) ---

    suspend fun cloudGetConfig(ip: String): RpcResult<CloudConfigResult> =
        call(ip, "Cloud.GetConfig", null, CloudConfigResult.serializer())

    suspend fun cloudGetStatus(ip: String): RpcResult<CloudStatusResult> =
        call(ip, "Cloud.GetStatus", null, CloudStatusResult.serializer())

    /** Prend effet immédiatement, sans redémarrage (validé en direct le 2026-08-20). */
    suspend fun cloudSetConfig(ip: String, enable: Boolean): RpcResult<SetConfigResult> =
        call(
            ip,
            "Cloud.SetConfig",
            buildJsonObject { put("config", buildJsonObject { put("enable", enable) }) },
            SetConfigResult.serializer(),
        )

    // --- Scripting (simulation de présence) ---

    suspend fun scriptList(ip: String): RpcResult<ScriptListResult> =
        call(ip, "Script.List", null, ScriptListResult.serializer())

    suspend fun scriptCreate(ip: String, name: String): RpcResult<ScriptCreateResult> =
        call(ip, "Script.Create", buildJsonObject { put("name", name) }, ScriptCreateResult.serializer())

    suspend fun scriptGetCode(ip: String, id: Int): RpcResult<ScriptGetCodeResult> =
        call(ip, "Script.GetCode", buildJsonObject { put("id", id) }, ScriptGetCodeResult.serializer())

    suspend fun scriptPutCode(ip: String, id: Int, code: String): RpcResult<ScriptPutCodeResult> =
        call(
            ip,
            "Script.PutCode",
            buildJsonObject {
                put("id", id)
                put("code", code)
                put("append", false)
            },
            ScriptPutCodeResult.serializer(),
        )

    suspend fun scriptSetConfig(ip: String, id: Int, enable: Boolean): RpcResult<SetConfigResult> =
        call(
            ip,
            "Script.SetConfig",
            buildJsonObject {
                put("id", id)
                put("config", buildJsonObject { put("enable", enable) })
            },
            SetConfigResult.serializer(),
        )

    suspend fun scriptStart(ip: String, id: Int): RpcResult<ScriptRunResult> =
        call(ip, "Script.Start", buildJsonObject { put("id", id) }, ScriptRunResult.serializer())

    suspend fun scriptStop(ip: String, id: Int): RpcResult<ScriptRunResult> =
        call(ip, "Script.Stop", buildJsonObject { put("id", id) }, ScriptRunResult.serializer())

    suspend fun scriptDelete(ip: String, id: Int): RpcResult<ScriptRunResult> =
        call(ip, "Script.Delete", buildJsonObject { put("id", id) }, ScriptRunResult.serializer())

    /**
     * Exécute [code] dans le contexte d'un script **déjà en cours d'exécution** — échoue avec
     * `-109` si ce n'est pas le cas. Contrairement à `Script.PutCode`+`Script.Stop`/`Start`, ne
     * réinitialise jamais les variables de haut niveau du script : validé en direct le 2026-08-18
     * (superviseur `hestia_charge`, voir [kapoue.hestia.data.presence.ChargeScriptGenerator]) —
     * seul moyen trouvé d'ajouter/retirer un canal surveillé sans perdre la mémoire des autres.
     */
    suspend fun scriptEval(ip: String, id: Int, code: String): RpcResult<ScriptEvalResult> =
        call(
            ip,
            "Script.Eval",
            buildJsonObject {
                put("id", id)
                put("code", code)
            },
            ScriptEvalResult.serializer(),
        )

    // --- Webhooks natifs (relais ntfy des détecteurs de fumée, lot 4a — voir SMOKE-DETECTOR.md) ---

    suspend fun webhookList(ip: String): RpcResult<WebhookListResult> =
        call(ip, "Webhook.List", null, WebhookListResult.serializer())

    /**
     * Le webhook natif ne sait faire qu'une simple requête **GET** par URL — pas de méthode POST,
     * pas de corps, pas d'en-tête personnalisable (vérifié dans la doc officielle Shelly le
     * 2026-09-01). [urls] vise donc toujours un autre appareil Shelly du réseau local
     * ([kapoue.hestia.data.presence.SmokeRelayScriptGenerator]), jamais ntfy directement.
     */
    suspend fun webhookCreate(ip: String, cid: Int, event: String, name: String, urls: List<String>): RpcResult<WebhookCreateResult> =
        call(
            ip,
            "Webhook.Create",
            buildJsonObject {
                put("cid", cid)
                put("enable", true)
                put("event", event)
                put("name", name)
                put("urls", buildJsonArray { urls.forEach { add(it) } })
            },
            WebhookCreateResult.serializer(),
        )

    suspend fun webhookDelete(ip: String, id: Int): RpcResult<ScriptRunResult> =
        call(ip, "Webhook.Delete", buildJsonObject { put("id", id) }, ScriptRunResult.serializer())

    // --- Schedule (planning natif de l'appareil) ---

    suspend fun scheduleList(ip: String): RpcResult<ScheduleListResult> =
        call(ip, "Schedule.List", null, ScheduleListResult.serializer())

    /**
     * Crée un programme cron : à [timespec], bascule le canal [channelId] sur [on]. Un seul appel
     * d'action du canal ([ChannelControl]) par défaut — c'est ce
     * qui permet de reconstruire les plannings côté repository.
     * [scriptCallMethod]/[scriptId] ajoutent un second appel (`Script.Start`/`Script.Stop`) dans
     * le même programme — validé sur Plug M Gen3 : les deux appels s'exécutent bien l'un après
     * l'autre (planning Unique avec coupure sur seuil). [ntfyTopic]/[ntfyTitle]/[ntfyBody]
     * ajoutent un appel `HTTP.Request` (POST) vers ntfy — toujours **après** l'action du canal pour
     * ne jamais retarder l'action réelle si ntfy.sh est lent ou injoignable (timeout court, 5 s).
     * `HTTP.POST` ne permet pas d'en-têtes personnalisés (pas de titre) : `HTTP.Request` si.
     */
    suspend fun scheduleCreate(
        ip: String,
        timespec: String,
        control: ChannelControl,
        channelId: Int,
        on: Boolean,
        scriptCallMethod: String? = null,
        scriptId: Int? = null,
        // Code JS à évaluer quand [scriptCallMethod] vaut "Script.Eval" (extinction d'un planning
        // avec seuil : le script décide lui-même s'il notifie la fin, voir planEnd dans
        // ChargeScriptGenerator.generate).
        scriptCode: String? = null,
        ntfyTopic: String? = null,
        ntfyTitle: String? = null,
        ntfyBody: String? = null,
        enable: Boolean = true,
    ): RpcResult<ScheduleCreateResult> = call(
        ip,
        "Schedule.Create",
        buildJsonObject {
            put("enable", enable)
            put("timespec", timespec)
            put(
                "calls",
                buildJsonArray {
                    add(scheduleActionCall(control, channelId, on))
                    if (scriptCallMethod != null && scriptId != null) {
                        add(
                            buildJsonObject {
                                put("method", scriptCallMethod)
                                put(
                                    "params",
                                    buildJsonObject {
                                        put("id", scriptId)
                                        if (scriptCode != null) put("code", scriptCode)
                                    },
                                )
                            },
                        )
                    }
                    if (ntfyTopic != null && ntfyBody != null) {
                        add(ntfyCall(ntfyTopic, ntfyTitle, ntfyBody))
                    }
                },
            )
        },
        ScheduleCreateResult.serializer(),
    )

    /** Appel `HTTP.Request` (POST vers ntfy), partagé entre `Schedule.Create` et les scripts. */
    private fun ntfyCall(topic: String, title: String?, body: String): JsonObject = buildJsonObject {
        put("method", "HTTP.Request")
        put(
            "params",
            buildJsonObject {
                put("method", "POST")
                put("url", "https://ntfy.sh/$topic")
                put("body", body)
                put("timeout", 5)
                if (title != null) {
                    put("headers", buildJsonObject { put("Title", title) })
                }
            },
        )
    }

    suspend fun scheduleDelete(ip: String, id: Int): RpcResult<ScheduleDeleteResult> =
        call(ip, "Schedule.Delete", buildJsonObject { put("id", id) }, ScheduleDeleteResult.serializer())

    /**
     * Interroge l'appareil pour déduire ses capacités. Rejette les Gen1.
     * Combine Shelly.GetDeviceInfo (génération, modèle) et Shelly.GetComponents (canaux).
     */
    suspend fun probe(ip: String): RpcResult<DeviceCapabilities> {
        val info = when (val r = getDeviceInfo(ip)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return r
            is RpcResult.Failure -> return r
        }

        val generation = info.gen
        // gen absent ou < 2 ⇒ Gen1 (API non RPC) ou appareil non-Shelly : refus explicite.
        if (generation == null) return RpcResult.Failure(RpcFailure.NOT_SHELLY_GEN2)
        if (generation < 2) return RpcResult.Failure(RpcFailure.GEN1_UNSUPPORTED)

        val components = when (val r = getAllComponents(ip)) {
            is RpcResult.Success -> r.value
            is RpcResult.RpcError -> return r
            is RpcResult.Failure -> return r
        }

        val switchChannels = parseChannels(components, "switch")

        // Nom déjà configuré sur l'appareil pour chaque canal (Switch.GetConfig.name via le même
        // Shelly.GetComponents ci-dessus, aucun appel RPC de plus) — sert à proposer le vrai nom
        // du canal à l'ajout plutôt qu'un générique « <nom saisi> · N » (nom des prises, 2026-09-07).
        val channelNames = parseChannelNames(components, "switch")

        val hasPowerMetering = components.any { entry ->
            entry.key.startsWith("pm") || entry.key.startsWith("em") ||
                entry.status?.containsKey("apower") == true
        }

        val capabilities = DeviceCapabilities(
            generation = generation,
            model = info.model,
            reportedName = info.name,
            switchChannels = switchChannels,
            channelNames = channelNames,
            lightChannels = parseChannels(components, "light"),
            lightChannelNames = parseChannelNames(components, "light"),
            coverChannels = parseChannels(components, "cover"),
            coverChannelNames = parseChannelNames(components, "cover"),
            // Le moteur de scripts est standard sur Gen2+.
            hasScripting = generation >= 2,
            hasPowerMetering = hasPowerMetering,
            componentKeys = components.map { it.key },
        )
        // Composants bruts au journal : d'un rapport de bug, savoir exactement de quel appareil il
        // s'agit (relais, volet, variateur…) sans avoir à le demander à l'utilisateur.
        logger.info(
            DiagnosticLogger.RPC,
            "Sonde @ $ip → ${info.model} gen$generation, composants : ${capabilities.componentKeys.joinToString()}" +
                (capabilities.unsupportedKind?.let { " (aucun relais : $it)" } ?: ""),
        )
        return RpcResult.Success(capabilities)
    }

    /** Récupère tous les composants en paginant via `offset` jusqu'à `total` (borné). */
    private suspend fun getAllComponents(ip: String): RpcResult<List<kapoue.hestia.data.rpc.model.ComponentEntry>> {
        val collected = mutableListOf<kapoue.hestia.data.rpc.model.ComponentEntry>()
        var offset = 0
        var total = Int.MAX_VALUE
        var guard = 0
        while (collected.size < total && guard < MAX_COMPONENT_PAGES) {
            guard++
            val params = buildJsonObject {
                put("offset", offset)
                // "config" en plus de "status" : sert à lire le nom déjà configuré sur chaque
                // canal (nom des prises, 2026-09-07), sans appel RPC dédié.
                put("include", buildJsonArray { add("status"); add("config") })
            }
            when (val r = call(ip, "Shelly.GetComponents", params, ComponentsResult.serializer())) {
                is RpcResult.Success -> {
                    total = r.value.total.coerceAtLeast(r.value.components.size)
                    if (r.value.components.isEmpty()) break
                    collected += r.value.components
                    offset += r.value.components.size
                }
                is RpcResult.RpcError -> return r
                is RpcResult.Failure -> return r
            }
        }
        return RpcResult.Success(collected)
    }

    /** Appel générique typé. Journalise méthode, cible, code et durée. */
    internal suspend fun <T> call(
        ip: String,
        method: String,
        params: JsonElement?,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): RpcResult<T> = withContext(Dispatchers.IO) {
        // Filet de sécurité du mode démo (2026-10-29) : aucune requête ne part jamais vers les
        // adresses fictives, quel que soit l'appelant — upstream 2.17.0 a dû colmater isSmokeRelay
        // et resyncSmokeRelay un par un (3 s d'attente chacun). Les chemins démo connus répondent
        // en amont avec des données factices ; arriver ici trahit un oubli, d'où la trace.
        if (ip.startsWith(DeviceRepository.DEMO_IP_PREFIX)) {
            logger.info(DiagnosticLogger.RPC, "$method @ $ip → appareil démo, aucun appel réseau")
            return@withContext RpcResult.Failure(RpcFailure.UNREACHABLE)
        }
        // Échec fermé : sans les épingles enregistrées, un appareil épinglé partirait en HTTP clair.
        if (!access.ensureLoaded()) {
            logger.warn(DiagnosticLogger.RPC, "$method @ $ip → épingles HTTPS illisibles, appel refusé")
            return@withContext RpcResult.Failure(RpcFailure.UNREACHABLE)
        }
        // Mot de passe refusé ou pause après un 429 : aucune requête ne part.
        access.blockedFor(ip)?.let { blocked ->
            // Info, pas avertissement : relevé à chaque rafraîchissement tant que dure le blocage.
            logger.info(DiagnosticLogger.RPC, "$method @ $ip → appel bloqué (${blocked.name})")
            return@withContext RpcResult.Failure(blocked)
        }
        val id = requestIds.getAndIncrement()
        val payload = json.encodeToString(RpcRequest.serializer(), RpcRequest(id, method, params))
        // Adresse épinglée : HTTPS uniquement, avec ce seul certificat. Jamais de repli en HTTP.
        val pin = access.pinFor(ip)
        val (client, url) = if (pin != null) pinnedClient(ip, pin) to "https://$ip/rpc" else localClient to "http://$ip/rpc"
        // Protocole tracé sur chaque ligne (http/https) : TESTS.md vérifie « aucun appel http:// »
        // vers un appareil épinglé à partir du seul journal de diagnostic.
        val target = if (pin != null) "https://$ip" else "http://$ip"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val startedAt = System.currentTimeMillis()
        try {
            // Mot de passe connu : tout l'échange (envoi, défi, renvoi) est sérialisé par adresse,
            // pour que les compteurs nc d'une même session arrivent dans l'ordre à l'appareil.
            val exchange = if (access.passwordFor(ip) != null) {
                access.lockFor(ip).withLock {
                    // Re-vérifié SOUS le verrou : un appel en attente derrière un refus ou un 429
                    // ne doit envoyer aucune requête de plus, ni un mot de passe périmé.
                    access.blockedFor(ip)?.let { blocked ->
                        logger.info(DiagnosticLogger.RPC, "$method @ $target → appel bloqué (${blocked.name})")
                        return@withLock Exchange.Failed(blocked)
                    }
                    exchange(client, request, ip, target, method, access.passwordFor(ip))
                }
            } else {
                exchange(client, request, ip, target, method, null)
            }
            val sent = when (exchange) {
                is Exchange.Failed -> return@withContext RpcResult.Failure(exchange.failure)
                is Exchange.Done -> exchange
            }
            sent.response.use { response ->
                // Réponse obtenue avec un en-tête Authorization : le mot de passe envoyé est accepté
                // (2xx comme 5xx : l'authentification est passée), sauf sur une redirection.
                if (!response.isRedirect) sent.passwordSent?.let { access.onAuthAccepted(ip, it) }
                val elapsed = System.currentTimeMillis() - startedAt
                if (response.isRedirect) {
                    val location = response.header("Location").orEmpty()
                    if (location.startsWith("https://", ignoreCase = true)) {
                        logger.warn(DiagnosticLogger.RPC, "$method @ $target → redirigé vers HTTPS, certificat à confirmer (${elapsed}ms)")
                        access.report(ip, AccessIssue.HTTPS_REQUIRED)
                        return@withContext RpcResult.Failure(RpcFailure.HTTPS_REQUIRED)
                    }
                    logger.warn(DiagnosticLogger.RPC, "$method @ $target → redirection inattendue ${response.code} (${elapsed}ms)")
                    return@withContext RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE)
                }
                // L'appareil a répondu sans redirection : tout problème d'accès signalé est levé.
                access.report(ip, null)
                val body = response.body?.string().orEmpty()
                val envelope = runCatching { json.decodeFromString(RpcEnvelope.serializer(), body) }
                    .getOrElse {
                        logger.warn(DiagnosticLogger.RPC, "$method @ $target → réponse illisible (${elapsed}ms)")
                        return@withContext RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE)
                    }

                envelope.error?.let { err ->
                    logger.warn(DiagnosticLogger.RPC, "$method @ $target → erreur ${err.code} (${elapsed}ms)")
                    return@withContext RpcResult.RpcError(err.code, err.message)
                }

                // Certaines méthodes d'action (Shelly.Update, Shelly.Reboot, Script.Delete…)
                // renvoient un résultat null en cas de succès (confirmé par David le 2026-09-25 :
                // Shelly.Update rapportait un échec dans Hestia alors que la prise avait bel et
                // bien été mise à jour) : traité comme un objet vide, décodé vers les valeurs par
                // défaut du type. `envelope.result` seul ne suffisait pas — un littéral JSON
                // `null` explicite (pas juste la clé absente) se décode en `JsonNull`, une
                // instance bien réelle de JsonElement, jamais interceptée par un simple `?:`.
                val result = envelope.result?.takeUnless { it == JsonNull } ?: JsonObject(emptyMap())

                val decoded = runCatching { json.decodeFromJsonElement(serializer, result) }
                    .getOrElse {
                        logger.warn(DiagnosticLogger.RPC, "$method @ $target → résultat inattendu (${elapsed}ms)")
                        return@withContext RpcResult.Failure(RpcFailure.MALFORMED_RESPONSE)
                    }

                // Switch.GetStatus est relevé toutes les ~5 s par appareil pendant que le Tableau ou
                // le détail est ouvert : le journaliser en succès noierait le tampon circulaire de
                // routine, au détriment des entrées vraiment utiles à un diagnostic. Les échecs
                // (avertissements ci-dessus/ci-dessous) restent journalisés, eux, dans tous les cas.
                if (method !in QUIET_ON_SUCCESS) {
                    logger.info(DiagnosticLogger.RPC, "$method @ $target → ${response.code} (${elapsed}ms)")
                }
                RpcResult.Success(decoded)
            }
        } catch (e: SocketTimeoutException) {
            logger.warn(DiagnosticLogger.RPC, "$method @ $target → timeout")
            RpcResult.Failure(RpcFailure.TIMEOUT)
        } catch (e: java.io.InterruptedIOException) {
            // callTimeout global d'OkHttp.
            logger.warn(DiagnosticLogger.RPC, "$method @ $target → timeout (callTimeout)")
            RpcResult.Failure(RpcFailure.TIMEOUT)
        } catch (e: ConnectException) {
            logger.warn(DiagnosticLogger.RPC, "$method @ $target → injoignable")
            RpcResult.Failure(RpcFailure.UNREACHABLE)
        } catch (e: IOException) {
            if (pin != null && e.hasPinMismatch()) {
                // Certificat différent de l'épingle : refus net, aucun nouvel essai (ni HTTP ni autre).
                logger.warn(DiagnosticLogger.RPC, "$method @ $target → certificat différent de l'épingle, appel refusé")
                access.report(ip, AccessIssue.CERT_CHANGED)
                return@withContext RpcResult.Failure(RpcFailure.CERT_CHANGED)
            }
            logger.warn(DiagnosticLogger.RPC, "$method @ $target → échec réseau : ${e.javaClass.simpleName}")
            RpcResult.Failure(RpcFailure.UNREACHABLE)
        } catch (e: SecurityException) {
            // Accès au réseau local refusé (Android 17) : échec propre, jamais de plantage (cf. peekCertificate).
            logger.warn(DiagnosticLogger.RPC, "$method @ $target → accès réseau local refusé")
            RpcResult.Failure(RpcFailure.UNREACHABLE)
        }
    }

    /** Issue de l'échange authentifié : réponse finale à traiter, ou échec décidé sans elle. */
    private sealed interface Exchange {
        /** [passwordSent] : mot de passe réellement envoyé sur la requête qui a obtenu [response]. */
        class Done(val response: Response, val passwordSent: String?) : Exchange
        class Failed(val failure: RpcFailure) : Exchange
    }

    /**
     * Envoie [request], répond au défi digest éventuel par **un seul** renvoi authentifié, et
     * renvoie la réponse finale (non lue) ou l'échec décidé. Les corps des 401/429 intermédiaires
     * sont fermés ici. Ne journalise jamais l'en-tête Authorization ni le mot de passe.
     */
    private suspend fun exchange(
        client: OkHttpClient,
        request: Request,
        ip: String,
        target: String,
        method: String,
        password: String?,
    ): Exchange {
        // Jamais d'identifiants en HTTP clair vers un appareil signalé HTTPS : ni préemptifs ni renvoi.
        // httpsSeen est collant pour le processus : un 200 HTTP intermédiaire n'efface pas la consigne.
        val clearTextForbidden = target.startsWith("http://") && (
            access.httpsSeen(ip) || access.issues.value[ip].let {
                it == AccessIssue.HTTPS_REQUIRED || it == AccessIssue.CERT_CHANGED
            }
            )
        // Session réutilisée seulement sur la même cible (http://ip ≠ https://ip).
        val existing = if (password != null && !clearTextForbidden) access.sessionFor(target) else null
        val firstRequest = if (existing != null) {
            authorized(request, existing, password!!) ?: return malformedChallenge(ip, target, method)
        } else {
            request
        }
        val first = client.newCall(firstRequest).execute()
        when (first.code) {
            HTTP_TOO_MANY_REQUESTS -> {
                first.close()
                return throttled(ip, target, method)
            }
            HTTP_UNAUTHORIZED -> Unit
            else -> return Exchange.Done(first, if (existing != null) password else null)
        }
        val challenge = first.headers("WWW-Authenticate").firstNotNullOfOrNull(DigestAuth::parseChallenge)
        first.close()
        if (clearTextForbidden) {
            logger.warn(DiagnosticLogger.RPC, "$method @ $target → authentification demandée en HTTP, HTTPS requis : aucun envoi")
            // Signalé comme tout HTTPS_REQUIRED : la tuile et Modifier proposent d'accepter le certificat.
            access.report(ip, AccessIssue.HTTPS_REQUIRED)
            return Exchange.Failed(RpcFailure.HTTPS_REQUIRED)
        }
        if (password == null) {
            if (existing != null) access.setSession(target, null)
            logger.warn(DiagnosticLogger.RPC, "$method @ $target → authentification demandée, aucun mot de passe")
            access.report(ip, AccessIssue.PASSWORD_REQUIRED)
            return Exchange.Failed(RpcFailure.PASSWORD_REQUIRED)
        }
        // Mot de passe connu mais défi absent ou inexploitable : réponse inattendue, aucun renvoi.
        if (challenge == null) return malformedChallenge(ip, target, method)
        // Nouveau défi (premier contact ou nonce expiré) : nouvelle session, un seul renvoi.
        logger.info(DiagnosticLogger.RPC, "$method @ $target → authentification demandée")
        val session = DigestSession(challenge.realm, challenge.nonce)
        access.setSession(target, session)
        val secondRequest = authorized(request, session, password) ?: return malformedChallenge(ip, target, method)
        val second = client.newCall(secondRequest).execute()
        return when (second.code) {
            HTTP_TOO_MANY_REQUESTS -> {
                second.close()
                throttled(ip, target, method)
            }
            HTTP_UNAUTHORIZED -> {
                // 401 sur un nonce tout frais : le mot de passe est faux, plus aucun essai.
                second.close()
                access.setSession(target, null)
                // Le registre signale PASSWORD_WRONG s'il applique le refus (un résultat périmé est ignoré).
                access.onAuthRejected(ip, password)
                logger.warn(DiagnosticLogger.RPC, "$method @ $target → mot de passe refusé")
                Exchange.Failed(RpcFailure.PASSWORD_WRONG)
            }
            else -> Exchange.Done(second, password)
        }
    }

    /**
     * Copie de [request] portant l'en-tête digest du compteur suivant de [session] ; `null` si
     * l'en-tête est invalide pour OkHttp (filet de sécurité : [DigestAuth.parseChallenge] filtre déjà
     * realm et nonce). Jamais d'exception : un défi hostile ne doit pas faire planter un relevé.
     */
    private fun authorized(request: Request, session: DigestSession, password: String): Request? = try {
        request.newBuilder()
            .header(
                "Authorization",
                DigestAuth.authorizationHeader(session.realm, session.nonce, session.nextNc(), DigestAuth.newCnonce(), password),
            )
            .build()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Défi digest inexploitable alors qu'un mot de passe est connu : session oubliée, aucun envoi. */
    private fun malformedChallenge(ip: String, target: String, method: String): Exchange {
        access.setSession(target, null)
        logger.warn(DiagnosticLogger.RPC, "$method @ $target → défi d'authentification illisible")
        return Exchange.Failed(RpcFailure.MALFORMED_RESPONSE)
    }

    /** HTTP 429 : pause pour [ip], signalée, sans aucun nouvel essai. */
    private fun throttled(ip: String, target: String, method: String): Exchange {
        access.onThrottled(ip)
        access.report(ip, AccessIssue.THROTTLED)
        logger.warn(DiagnosticLogger.RPC, "$method @ $target → limité (429)")
        return Exchange.Failed(RpcFailure.THROTTLED)
    }

    /** Cherche une [PinMismatchException] dans la chaîne de causes (bornée, contre les cycles). */
    private fun Throwable.hasPinMismatch(): Boolean {
        var current: Throwable? = this
        repeat(MAX_CAUSE_DEPTH) {
            val t = current ?: return false
            if (t is PinMismatchException) return true
            current = t.cause
        }
        return false
    }

    private companion object {
        /**
         * Lectures de routine relevées en boucle (Tableau toutes les ~5 s, Réglages toutes les 60 s,
         * une fois par canal d'un même appareil) : les journaliser en succès faisait tourner tout
         * le tampon circulaire de 250 entrées en ~80 s (constaté le 2026-09-29 sur un Strip4 :
         * impossible de retrouver le lancement d'un minuteur, déjà évincé quand le journal est
         * ouvert). Les échecs restent journalisés dans tous les cas.
         */
        val QUIET_ON_SUCCESS = setOf(
            "Switch.GetStatus", "Light.GetStatus", "Cover.GetStatus", "Schedule.List", "Script.List", "Script.GetCode", "Shelly.GetStatus",
        )
        const val MAX_COMPONENT_PAGES = 32
        const val HTTPS_PORT = 443
        const val PEEK_TIMEOUT_MS = 5_000
        const val MAX_CAUSE_DEPTH = 8
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** Empreintes tronquées dans le journal (≤ 11 caractères, « AB:CD:EF:01 »). */
        const val LOGGED_FINGERPRINT_CHARS = 11
    }
}
