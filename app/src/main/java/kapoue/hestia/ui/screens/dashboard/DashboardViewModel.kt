package kapoue.hestia.ui.screens.dashboard

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.notifications.NtfyClient
import kapoue.hestia.data.prefs.AppPreferences
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.getOrNull
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.repository.DeviceStatusResult
import kapoue.hestia.domain.model.Planning
import kapoue.hestia.domain.model.isActiveNow
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DeviceRepository,
    private val appPreferences: AppPreferences,
    private val logger: DiagnosticLogger,
    private val ntfyClient: NtfyClient,
) : ViewModel() {

    init {
        // Correctif ponctuel, sûr à rappeler à chaque lancement (sans effet une fois les noms
        // déjà au bon format) : voir DeviceRepository.fixLegacyChannelNames.
        viewModelScope.launch { repository.fixLegacyChannelNames() }
    }

    /**
     * Outil de support (pas une fonctionnalité) : envoie d'un coup tous les textes de notif
     * possibles, pour les relire et les valider sans attendre que chaque cas réel se produise.
     * Déclenché par 3 appuis rapprochés sur le titre du Tableau — jamais visible ailleurs.
     */
    fun debugSendAllNtfyTexts() {
        if (!appPreferences.ntfyEnabled.value) return
        val topic = appPreferences.ntfyTopic.value ?: return
        viewModelScope.launch {
            val title = "Hestia (test)"
            listOf(
                context.getString(R.string.notif_planning_started, "09:00", "17:00"),
                context.getString(R.string.notif_planning_ended, "09:00", "17:00"),
                context.getString(R.string.notif_ntfy_presence_started),
                context.getString(R.string.notif_ntfy_presence_ended),
                context.getString(R.string.notif_timer_ended, "30 min"),
                context.getString(R.string.notif_cutoff_triggered),
            ).forEach { body ->
                ntfyClient.send(topic, title, body)
                delay(1_500)
            }
        }
    }

    private val statuses = MutableStateFlow<Map<Long, TileStatus>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)

    // Simulations de présence réellement en cours, relevées sur les appareils à chaque cycle.
    private val presences = MutableStateFlow<Map<Long, PresenceInfo>>(emptyMap())

    // Plannings présents sur chaque appareil (la tuile affiche celui en cours, le cas échéant).
    private val plannings = MutableStateFlow<Map<Long, List<Planning>>>(emptyMap())

    // Seuil du minuteur en attente par appareil (mémo local, lecture instantanée — pas de RPC).
    // La tuile ne l'affiche que si l'appareil confirme lui-même un minuteur en cours.
    private val pendingThresholds = MutableStateFlow<Map<Long, Int>>(emptyMap())

    // Instant d'allumage du canal (référentiel SystemClock.elapsedRealtime), déduit du compteur
    // natif counts.on_time — voir le calcul dans fetch() et la doc de TileUiState.onSinceElapsed.
    private val onSinceElapsed = MutableStateFlow<Map<Long, Long>>(emptyMap())

    // Renseigné par la couche UI (qui seule connaît le Context) à chaque reprise d'écran.
    private val _permissionUsable = MutableStateFlow(true)

    /** Lu par l'écran pour afficher (ou non) le bandeau de permission manquante. */
    val permissionUsable: StateFlow<Boolean> = _permissionUsable.asStateFlow()

    // combine plafonne à 5 flux typés : on regroupe présence + planning + seuils + on_since en un seul.
    private val extras = combine(presences, plannings, pendingThresholds, onSinceElapsed) { p, pl, th, os -> Extras(p, pl, th, os) }

    val uiState: StateFlow<DashboardUiState> =
        combine(repository.observeDevices(), statuses, refreshing, loaded, extras) { devices, statusMap, isRefreshing, isLoaded, extras ->
            val ordered = repository.groupedForDisplay(devices)
            val byIp = ordered.groupBy { it.ipAddress }
            var lastIp: String? = null
            DashboardUiState(
                tiles = ordered.map { device ->
                    val members = byIp.getValue(device.ipAddress)
                    val isMultiChannel = members.size > 1
                    val isFirstInGroup = device.ipAddress != lastIp
                    lastIp = device.ipAddress
                    TileUiState(
                        device = device,
                        status = statusMap[device.id] ?: TileStatus.Loading,
                        presence = extras.presence[device.id],
                        plannings = extras.plannings[device.id].orEmpty(),
                        pendingThresholdW = extras.pendingThresholds[device.id],
                        onSinceElapsed = extras.onSinceElapsed[device.id],
                        // Lecture locale pure (SharedPreferences), pas de RPC : recalculée à
                        // chaque recomposition de ce combine, pas besoin d'un flux dédié.
                        presenceDisabledToday = appPreferences.isPresenceDisabledToday(device.id),
                        planningDisabledToday = appPreferences.isPlanningDisabledToday(device.id),
                        groupLabel = groupDisplayName(members),
                        isFirstInGroup = isFirstInGroup,
                        isMultiChannel = isMultiChannel,
                    )
                },
                isRefreshing = isRefreshing,
                loaded = isLoaded,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** Mis à jour à chaque reprise d'écran. Si la permission tombe, le bandeau global le reflète. */
    fun updatePermission(usable: Boolean) {
        _permissionUsable.value = usable
    }

    private var refreshJob: Job? = null

    /**
     * Interroge tous les appareils en parallèle. Ne relance jamais tout seul en boucle.
     *
     * L'indicateur de rafraîchissement (spinner « pull to refresh ») n'apparaît **que** pour un
     * tirage manuel (`userInitiated`) — jamais pour un cycle automatique (arrivée, polling toutes
     * les 5 s, `force`), quelle que soit sa durée. Un cycle automatique lent ne doit jamais se
     * traduire par un spinner qui clignote tout seul sans action de l'utilisateur.
     */
    fun refresh(userInitiated: Boolean = false, force: Boolean = false) {
        // Ne pas empiler les cycles de polling ; un tirage manuel (ou un relevé forcé) relance
        // en priorité. [force] sert p. ex. à confirmer vite l'extinction en fin de créneau de
        // planning, sans afficher le spinner immédiatement (indicateur temporisé comme un cycle
        // automatique).
        if (!userInitiated && !force && refreshJob?.isActive == true) return
        if (userInitiated || force) refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!_permissionUsable.value) {
                // Aucune interrogation tentée, aucune tuile affichée : le bandeau global du
                // Tableau porte le message et l'action (voir DashboardScreen.PermissionBanner).
                loaded.value = true
                return@launch
            }
            val devices = repository.getDevicesOnce()
            val fetch = launch {
                // États, présences et plannings relevés en parallèle : le temps total reste celui
                // du plus lent, pas la somme.
                val statusResults = async {
                    // Local d'abord pour chacun, puis un seul appel cloud groupé pour ceux
                    // injoignables (voir DeviceRepository.getStatuses) — jamais un appel cloud par
                    // appareil, jamais de sondage cloud pour un appareil déjà joignable en local.
                    val byDevice = repository.getStatuses(devices)
                    devices.map { device ->
                        val statusResult = byDevice[device.id]
                            ?: DeviceStatusResult(RpcResult.Failure(RpcFailure.UNREACHABLE), viaCloud = false)
                        val tileStatus = statusResult.toTileStatus()
                        // Rattrapage ntfy best-effort : l'appareil répond, on en profite pour
                        // vérifier s'il a raté une resynchro (aucune requête si déjà à jour).
                        // Détaché du cycle de relevé (pas annulé par un tirage manuel suivant).
                        if (tileStatus is TileStatus.Online) {
                            viewModelScope.launch { repository.ntfyCatchUpIfNeeded(device) }
                        }
                        device.id to tileStatus
                    }
                }
                val presenceResults = async {
                    devices.filter { it.hasScripting }.map { device ->
                        async { device.id to loadPresence(device) }
                    }.awaitAll()
                }
                val planningResults = async {
                    devices.filter { it.supportsSwitch }.map { device ->
                        async { device.id to repository.getPlannings(device).getOrNull().orEmpty() }
                    }.awaitAll()
                }
                // Relevé précédent conservé avant écrasement — sert de référence ci-dessous pour
                // exiger une vraie transition allumé→éteint, pas juste « actuellement éteint ».
                val previousStatuses = statuses.value
                statuses.value = statusResults.await().toMap()
                presences.value = presenceResults.await()
                    .mapNotNull { (id, info) -> info?.let { id to it } }
                    .toMap()
                plannings.value = planningResults.await().toMap()
                // Planning récurrent désactivé pour aujourd'hui : un appui bouton physique pendant
                // sa fenêtre est détectable sans rien changer côté appareil (pas de script à
                // modifier, contrairement à la présence) — juste mémoriser localement, la coupure
                // elle-même est déjà faite par l'appui. Lecture locale pure (source déjà dans le
                // relevé de statut de ce cycle), jamais de RPC en plus.
                //
                // [wasOn] : bug trouvé en direct le 2026-08-24 — sans cette exigence, une source
                // bouton qui traîne depuis des heures (dernier appui réel bien plus tôt, prise
                // restée éteinte depuis) redéclenchait la détection à **chaque** cycle de 5 s,
                // indéfiniment, empêchant toute présence/planning nouvellement créé sur ce canal de
                // s'allumer un jour. Exiger que le cycle **précédent** ait vu la prise allumée
                // restreint la détection à une vraie transition allumé→éteint survenue entre deux
                // cycles — un vrai appui, pas un souvenir. Contrepartie acceptée : un appui survenu
                // pendant que l'app était fermée (rien à comparer au premier relevé) ne sera pas
                // rattrapé rétroactivement — la présence elle-même continue de tourner en autonomie
                // sur l'appareil (voir CLAUDE.md), l'utilisateur peut toujours couper à la main.
                devices.filter { it.supportsSwitch }.forEach { device ->
                    val status = statuses.value[device.id] as? TileStatus.Online ?: return@forEach
                    if (status.output || !isButtonSource(status.source)) return@forEach
                    val wasOn = (previousStatuses[device.id] as? TileStatus.Online)?.output == true
                    if (!wasOn) return@forEach
                    val activePlanning = plannings.value[device.id]
                        ?.firstOrNull { !it.isPresence && !it.once && it.isActiveNow() }
                    if (activePlanning != null) appPreferences.markPlanningDisabledToday(device.id)
                }
                // Même détection pour un appui bouton pendant une présence — mais contrairement à
                // Planning, le script tourne en continu et réimposerait l'état voulu au tick
                // suivant si on se contentait de mémoriser localement. Il faut donc réellement
                // déclencher la coupure du jour (même appel que le bouton de l'app), pas juste
                // l'enregistrer — coûte un vrai aller-retour RPC, mais seulement dans ce cas rare
                // précis (canal éteint, source bouton, présence active, pas déjà marqué) : jamais
                // pour tous les canaux à chaque cycle. Même garde [wasOn] que ci-dessus, pour la
                // même raison (sans elle, `stopPresenceForToday` était rappelé en boucle dès qu'une
                // présence fraîchement créée croisait une vieille source bouton).
                devices.filter { it.hasScripting }.forEach { device ->
                    val status = statuses.value[device.id] as? TileStatus.Online ?: return@forEach
                    if (status.output || !isButtonSource(status.source)) return@forEach
                    val wasOn = (previousStatuses[device.id] as? TileStatus.Online)?.output == true
                    if (!wasOn) return@forEach
                    if (appPreferences.isPresenceDisabledToday(device.id)) return@forEach
                    if (presences.value[device.id] == null) return@forEach
                    if (repository.stopPresenceForToday(device) is RpcResult.Success) {
                        appPreferences.markPresenceDisabledToday(device.id)
                        logger.info(
                            DiagnosticLogger.RPC,
                            "Présence coupée pour aujourd'hui (appui bouton physique détecté, ${device.ipAddress})",
                        )
                    }
                }
                // Seuil d'un minuteur « sans limite de durée » surveillé par hestia_charge (lancé
                // depuis l'app) ou hestia_button_timer (armé par un vrai appui bouton — deux
                // scripts distincts, jamais les deux à la fois pour un même canal), pour les
                // canaux « Actif » sans autre explication (pas de décompte natif, pas de présence,
                // pas de planning en cours) — le seul cas où ce seuil serait sinon invisible
                // (aucun `timerEndsAtElapsed` pour le signaler autrement, voir BACKLOG.md). Coûte
                // un Script.List + Eval par canal concerné (× 2 scripts vérifiés), mais seulement
                // pour ceux-là, jamais pour tous les canaux à chaque cycle (voir
                // DeviceRepository.getActiveChargeThreshold/getActiveButtonThreshold, mécanismes
                // validés en direct le 2026-08-22).
                val activeChargeThresholds = devices.filter { it.hasScripting }.map { device ->
                    async {
                        val status = statuses.value[device.id] as? TileStatus.Online
                        val hasActivePlanning = plannings.value[device.id]?.any { it.isActiveNow() } == true
                        val bareActive = status != null && status.output && status.timerEndsAtElapsed == null &&
                            presences.value[device.id] == null && !hasActivePlanning
                        if (!bareActive) return@async null
                        val threshold = repository.getActiveChargeThreshold(device)
                            ?: repository.getActiveButtonThreshold(device)
                        threshold?.let { device.id to it }
                    }
                }.awaitAll().filterNotNull().toMap()
                // Lecture locale (SharedPreferences), pas de RPC : pas besoin de la paralléliser.
                // Ce souvenir ne date que des minuteurs lancés depuis l'app (Manuel/Perso) — un
                // minuteur bouton (armé par un appui physique) ne le touche jamais, ni pour
                // l'écrire ni pour l'effacer. Sans vérification, un vieux souvenir resterait donc
                // affiché indéfiniment (jusqu'au prochain passage du nettoyage en tâche de fond,
                // toutes les 15 min) même sur un tout autre minuteur en cours, y compris sans
                // seuil — bug vécu en direct le 2026-08-18. On ne le garde que s'il correspond
                // (à quelques secondes près) au minuteur natif réellement en cours sur l'appareil ;
                // sinon on l'efface tout de suite, pas la peine d'attendre le nettoyage périodique.
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                // Durée du ON en cours, déduite de counts.on_time (compteur natif cumulé, jamais
                // remis à zéro par Hestia) : on retient sa valeur à chaque extinction observée, la
                // différence avec la valeur actuelle donne la durée exacte du allumage en cours —
                // fiable même après une app fermée ou hors réseau entre-temps, contrairement à un
                // simple horodatage local pris à la première ouverture qui voit le canal allumé
                // (voir échange avec David, 2026-08-22).
                //
                // Le point de départ (onSinceEpoch) n'est calculé QU'UNE FOIS par allumage, au
                // premier cycle où on le voit allumé avec une référence d'extinction connue —
                // jamais recalculé aux cycles suivants tant que le canal reste allumé. Recalculer
                // à chaque cycle à partir de la dernière valeur de on_time faisait dériver
                // l'affichage (précision du compteur natif pas garantie à la seconde près d'un
                // cycle à l'autre) : le compte à rebours semblait revenir en arrière en rouvrant
                // la modale — bug remonté par David le 2026-08-22. Rien à afficher tant qu'aucune
                // référence n'a encore été observée (première fois que Hestia voit ce canal).
                onSinceElapsed.value = statuses.value.mapNotNull { (deviceId, status) ->
                    if (status !is TileStatus.Online) return@mapNotNull null
                    if (!status.output) {
                        status.onTimeSec?.let { appPreferences.setOnTimeBaseline(deviceId, it) }
                        appPreferences.clearOnSinceEpoch(deviceId)
                        return@mapNotNull null
                    }
                    var epoch = appPreferences.onSinceEpoch(deviceId)
                    if (epoch == null) {
                        val onTimeSec = status.onTimeSec ?: return@mapNotNull null
                        val baseline = appPreferences.onTimeBaseline(deviceId) ?: return@mapNotNull null
                        val streakSec = onTimeSec - baseline
                        if (streakSec < 0) return@mapNotNull null // compteur remis à zéro côté appareil, référence invalide
                        epoch = nowWall - (streakSec * 1000).toLong()
                        appPreferences.setOnSinceEpoch(deviceId, epoch)
                    }
                    deviceId to (nowElapsed - (nowWall - epoch))
                }.toMap()
                // Fusionné avec le seuil calculé ci-dessus pour les canaux "Actif" sans décompte :
                // même concept (un seuil surveille actuellement ce canal), deux sources selon le
                // cas (souvenir local confirmé par le minuteur natif, ou lecture live du script).
                pendingThresholds.value = appPreferences.pendingTimers().mapNotNull { timer ->
                    val thresholdW = timer.thresholdW ?: return@mapNotNull null
                    val deviceEndsAtElapsed = (statuses.value[timer.deviceId] as? TileStatus.Online)?.timerEndsAtElapsed
                    val remainingLocalSec = (timer.endMillis - nowWall) / 1000
                    val remainingDeviceSec = deviceEndsAtElapsed?.let { (it - nowElapsed) / 1000 }
                    if (remainingDeviceSec == null || abs(remainingDeviceSec - remainingLocalSec) > STALE_PENDING_TIMER_TOLERANCE_SEC) {
                        appPreferences.removePendingTimer(timer.deviceId)
                        return@mapNotNull null
                    }
                    timer.deviceId to thresholdW
                }.toMap() + activeChargeThresholds
                loaded.value = true
            }
            if (userInitiated) refreshing.value = true
            fetch.join()
            if (userInitiated) refreshing.value = false
        }
    }

    /**
     * Bascule un canal, puis relit son état réel (jamais supposé). Couper alors qu'un planning
     * **récurrent** (jamais Unique, pas de « lendemain » à distinguer) est en cours désactive ce
     * planning pour aujourd'hui (mémo local, voir `AppPreferences.markPlanningDisabledToday`) —
     * pas de dialogue, contrairement à la présence : un planning ne dépend d'aucun script Hestia,
     * l'action est déjà sans risque (retour David, 2026-08-22).
     */
    fun toggle(device: Device, turnOn: Boolean) {
        if (!_permissionUsable.value) return
        viewModelScope.launch {
            if (!turnOn) {
                val activePlanning = plannings.value[device.id]?.firstOrNull { !it.isPresence && !it.once && it.isActiveNow() }
                if (activePlanning != null) appPreferences.markPlanningDisabledToday(device.id)
            }
            applyToggle(device, turnOn)
        }
    }

    /**
     * Coupe la présence **pour aujourd'hui seulement** — seule action du bouton ON/OFF pendant
     * qu'une présence est active, jamais suivie d'une bascule (visuellement un interrupteur
     * ON/OFF, l'utilisateur s'attend à couper le programme en cours, pas à en relancer un autre
     * à la place). Ne touche pas la configuration permanente (jours, horaires, marge) : la
     * présence reprend normalement le lendemain — arrêt définitif réservé à une suppression
     * explicite depuis l'écran Détail (retour David, 2026-08-22 : un simple bouton ON/OFF ne
     * doit pas supprimer une config récurrente, trop radical pour « je rentre, j'éteins pour
     * aujourd'hui »).
     */
    fun stopPresenceToday(device: Device) {
        if (!_permissionUsable.value) return
        viewModelScope.launch {
            when (repository.stopPresenceForToday(device)) {
                is RpcResult.Success -> {
                    logger.info(DiagnosticLogger.RPC, "Présence coupée pour aujourd'hui depuis le Tableau (${device.ipAddress})")
                    appPreferences.markPresenceDisabledToday(device.id)
                    fetchOne(device)
                }
                else -> setStatus(device.id, TileStatus.Offline)
            }
        }
    }

    private suspend fun applyToggle(device: Device, turnOn: Boolean) {
        // Éteindre reste toujours un Switch.Set direct, quel que soit ce qui a déclenché
        // l'allumage (bouton physique ou app) — jamais concerné par le minuteur bouton.
        val result = if (turnOn) startRespectingButtonTimer(device) else repository.userToggle(device, false)
        when (result) {
            is RpcResult.Success -> {
                logger.info(DiagnosticLogger.RPC, "Bascule ${device.ipAddress}#${device.switchId} → $turnOn")
                fetchOne(device)
            }
            else -> setStatus(device.id, TileStatus.Offline)
        }
    }

    /**
     * Allumer depuis l'app reprend la même durée/seuil que le minuteur configuré pour le bouton
     * physique de cet appareil, s'il y en a un (2026-08-17) — sinon un allumage classique,
     * immédiat et indéfini, comme avant. Limite connue : le script du minuteur bouton distingue
     * une annulation volontaire (appui bouton) d'une fin naturelle via le champ `source` de
     * l'appareil, détecté par le matériel — un allumage déclenché ici ne peut pas se faire passer
     * pour un appui physique, seule la notification de fin en serait potentiellement affectée,
     * jamais l'action elle-même.
     */
    private suspend fun startRespectingButtonTimer(device: Device): RpcResult<*> {
        val config = repository.getButtonTimerConfig(device)
        if (!config.enabled) return repository.userToggle(device, true)
        val detail = device.name
        return when {
            config.durationSeconds == null && config.thresholdW != null ->
                repository.startUnlimitedChargeTimer(device, config.thresholdW)
            config.durationSeconds != null && config.thresholdW != null ->
                repository.startChargeTimer(device, config.durationSeconds, config.thresholdW, detail)
            config.durationSeconds != null ->
                repository.startTimer(device, config.durationSeconds, detail)
            // Config incohérente (ni durée ni seuil) : ne devrait pas arriver, repli sûr.
            else -> repository.userToggle(device, true)
        }
    }

    /**
     * Simulation de présence en cours sur cet appareil, ou null. L'exécution est vérifiée sur
     * l'appareil (via [DeviceRepository.getPlannings], qui fusionne plannings précis et
     * simulations de présence depuis la fusion du 2026-08-18) ; les horaires viennent du cache
     * local (ils n'ont d'intérêt qu'affichés).
     */
    private suspend fun loadPresence(device: Device): PresenceInfo? {
        val plannings = repository.getPlannings(device).getOrNull().orEmpty()
        val active = plannings.firstOrNull { it.isPresence && it.isActiveNow() } ?: return null
        return PresenceInfo(active.startHour, active.startMinute, active.endHour, active.endMinute)
    }

    /**
     * Mode démo (build debug uniquement) : ajoute/retire des appareils fictifs pour les
     * captures d'écran. Déclenché par un appui long sur le titre « Hestia ».
     */
    fun toggleDemo() {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        viewModelScope.launch {
            if (repository.hasDemoDevices()) repository.removeDemoDevices() else repository.addDemoDevices()
        }
    }

    private suspend fun fetchOne(device: Device) {
        // getStatus (pas getStatuses) : un seul appareil, pas besoin du regroupement en un seul
        // appel cloud — cette fonction gère aussi le repli si nécessaire.
        setStatus(device.id, repository.getStatus(device).toTileStatus())
    }

    private fun setStatus(deviceId: Long, status: TileStatus) {
        statuses.value = statuses.value + (deviceId to status)
    }

    private companion object {
        /** Tolérance pour considérer qu'un souvenir local de minuteur correspond bien au minuteur
         * natif actuellement en cours sur l'appareil (voir le calcul dans [fetch]). */
        const val STALE_PENDING_TIMER_TOLERANCE_SEC = 5

        /** Même liste que `isButtonSource` côté script (voir `ButtonTimerScriptGenerator`) — la
         * valeur exacte varie selon le modèle (`button` sur Plug M, `short_push` sur Strip 4). */
        fun isButtonSource(source: String?): Boolean =
            source == "button" || source == "short_push" || source == "long_push" ||
                source == "double_push" || source == "triple_push"
    }
}

/**
 * Nom affiché dans l'en-tête : [Device.deviceName], stable et identique sur tous les canaux d'un
 * même appareil (renseigné une fois à l'ajout, jamais affecté par le renommage d'un canal
 * individuel — voir `DeviceRepository.fixLegacyChannelNames` pour les appareils enregistrés
 * avant l'existence de ce champ). Repli sur le nom du 1ᵉʳ canal dans le cas résiduel où il serait
 * encore vide (ne devrait pas arriver après le correctif de démarrage).
 */
private fun groupDisplayName(members: List<Device>): String {
    val deviceName = members.first().deviceName
    return deviceName.ifBlank { (members.minByOrNull { it.switchId } ?: members.first()).name }
}

/** Regroupe les flux dérivés annexes pour ne pas dépasser le plafond de 5 flux de `combine`. */
private data class Extras(
    val presence: Map<Long, PresenceInfo>,
    val plannings: Map<Long, List<Planning>>,
    val pendingThresholds: Map<Long, Int>,
    val onSinceElapsed: Map<Long, Long>,
)
