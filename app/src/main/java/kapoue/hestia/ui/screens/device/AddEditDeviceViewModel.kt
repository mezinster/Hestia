package kapoue.hestia.ui.screens.device

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kapoue.hestia.R
import kapoue.hestia.core.log.DiagnosticLogger
import kapoue.hestia.core.util.isValidIpv4
import kapoue.hestia.data.local.entity.Device
import kapoue.hestia.data.repository.CoverRepository
import kapoue.hestia.data.repository.DeviceRepository
import kapoue.hestia.data.repository.LightRepository
import kapoue.hestia.data.rpc.DeviceCapabilities
import kapoue.hestia.data.rpc.PeekedCertificate
import kapoue.hestia.data.rpc.RpcFailure
import kapoue.hestia.data.rpc.RpcResult
import kapoue.hestia.data.rpc.hasNoSwitchChannels
import kapoue.hestia.domain.model.CloudInfo
import kapoue.hestia.domain.model.DeviceType
import kapoue.hestia.domain.model.DriverType
import kapoue.hestia.domain.model.FirmwareCheckResult
import kapoue.hestia.domain.model.LedNightModeState
import kapoue.hestia.ui.common.UserMessage
import kapoue.hestia.ui.common.toUserMessage
import kapoue.hestia.ui.common.toUserMessageOrNull
import kapoue.hestia.ui.navigation.StackedRoutes
import kapoue.hestia.ui.permission.LocalNetworkPermission
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sélection des canaux détectés sur un appareil multi-canaux. */
data class ChannelSelection(
    val model: String?,
    val channels: List<Int>,
    val selected: Set<Int>,
)

/**
 * Certificat d'un appareil HTTPS-only, présenté à l'utilisateur avant toute confiance : [changed]
 * vrai quand il remplace un certificat déjà accepté (réutilisé en mode modification).
 */
data class CertificatePrompt(
    val ip: String,
    val certificate: PeekedCertificate,
    val changed: Boolean,
    /** Vrai si un mot de passe est enregistré : accepter le nouveau certificat le suspend (D1). */
    val passwordWillBeSuspended: Boolean = false,
)

data class AddEditUiState(
    val isEditMode: Boolean = false,
    /** Vrai si l'appareil édité a plusieurs canaux : le champ [name] représente alors le nom
     * de l'appareil physique (partagé), pas le nom d'un canal individuel. */
    val isGroupEdit: Boolean = false,
    val name: String = "",
    val ipAddress: String = "",
    /** Nom du 1ᵉʳ emplacement IP. Null = nom par défaut affiché (traduit, jamais stocké tel quel). */
    val ipName: String? = null,
    /** Deuxième adresse IP optionnelle. Null = un seul emplacement configuré. */
    val ip2Address: String? = null,
    val ip2Name: String? = null,
    val type: DeviceType = DeviceType.PLUG,
    /**
     * Mode ajout uniquement (toujours vrai en édition) : vrai une fois qu'une des deux tuiles
     * Prise/Détecteur a été choisie — avant ça, l'écran n'affiche que ce choix, rien d'autre
     * (retour David, 2026-09-23).
     */
    val typeChosen: Boolean = false,
    val isTesting: Boolean = false,
    val nameError: UserMessage? = null,
    val ipError: UserMessage? = null,
    /** Erreur globale (réseau, RPC, doublon). */
    val error: UserMessage? = null,
    /** Non nul lorsque plusieurs canaux ont été détectés et attendent une sélection. */
    val channelSelection: ChannelSelection? = null,
    /**
     * Ajout d'une prise uniquement : sonde automatique en cours (débounce après saisie de l'IP,
     * ou touche Suivant/OK du clavier) — distincte de [isTesting], qui couvre l'ajout final.
     */
    val autoProbing: Boolean = false,
    /**
     * Ajout d'une prise uniquement : nom déjà trouvé sur l'appareil par la dernière sonde
     * automatique réussie pour l'IP actuellement saisie. Non nul = le champ Nom disparaît, ce nom
     * est utilisé tel quel (retour David, 2026-09-23 : « si tu sais lire le nom de la prise,
     * plus besoin du champ Nom si un nom est trouvé »).
     */
    val foundDeviceName: String? = null,
    /**
     * Nom réellement retenu après un ajout réussi (prise ou détecteur), affiché quelques secondes
     * avant la fermeture automatique de l'écran — rassure que l'appareil a bien été contacté et lu,
     * pas seulement le texte saisi (retour David, 2026-09-23).
     */
    val confirmedDeviceName: String? = null,
    /** True quand l'opération est terminée : l'écran peut se refermer. */
    val done: Boolean = false,
    val isDirty: Boolean = false,
    /** LED d'état de l'appareil. Null = lecture en cours (mode édition seulement). */
    val ledState: LedNightModeState? = null,
    /** Pilote de l'appareil édité (Shelly Gen2+ pour l'instant) — conditionne le redémarrage,
     * propre au fabricant. Valeur par défaut sans portée hors mode édition. */
    val driver: DriverType = DriverType.SHELLY_GEN2,
    /** Résultat de la dernière vérification manuelle de mise à jour firmware. */
    val firmwareCheck: FirmwareCheckResult? = null,
    val firmwareChecking: Boolean = false,
    val firmwareInstalling: Boolean = false,
    /** Message transitoire de l'installation (démarrée ou en échec), consommé par l'UI. */
    val firmwareInstallMessage: UserMessage? = null,
    val rebooting: Boolean = false,
    /** Message transitoire du redémarrage (lancé ou en échec), consommé par l'UI. */
    val rebootMessage: UserMessage? = null,
    /** État Cloud Shelly. Null = lecture en cours (mode édition seulement). */
    val cloudInfo: CloudInfo? = null,
    val cloudToggling: Boolean = false,
    /** Non nul tant que l'utilisateur doit accepter ou refuser le certificat d'un appareil HTTPS. */
    val certificatePrompt: CertificatePrompt? = null,
    /** Vrai quand l'appareil exige un mot de passe : l'écran affiche le champ. */
    val passwordPrompt: Boolean = false,
    /** Saisie du mot de passe — jamais journalisée, vidée dès l'envoi. */
    val passwordInput: String = "",
    /** Connexion chiffrée de l'appareil édité ; null = HTTP simple, section masquée. */
    val tlsState: TlsState? = null,
    /** Section « Mot de passe de l'appareil » (mode édition) ; null = masquée (D5). */
    val passwordSection: PasswordSectionState? = null,
    /** Vrai si l'appareil n'a pas d'épingle : le mot de passe passe par un défi digest en HTTP clair. */
    val passwordHttpNote: Boolean = false,
)

@HiltViewModel
class AddEditDeviceViewModel @Inject constructor(
    private val repository: DeviceRepository,
    private val lightRepository: LightRepository,
    private val coverRepository: CoverRepository,
    private val logger: DiagnosticLogger,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingDeviceId: Long? =
        savedStateHandle.get<Long>(StackedRoutes.EDIT_DEVICE_ARG_ID)

    /** Défilement ponctuel jusqu'à la section Firmware à l'arrivée — voir le bandeau « Maj
     * dispo » du Tableau (2026-09-23), seul déclencheur pour l'instant. Lu une seule fois : pas
     * besoin de survivre à une recomposition, juste au tout premier affichage de l'écran. */
    val scrollToFirmwareOnLoad: Boolean =
        savedStateHandle.get<Boolean>(StackedRoutes.EDIT_DEVICE_ARG_SCROLL_TO_FIRMWARE) ?: false

    private val _uiState = MutableStateFlow(
        AddEditUiState(isEditMode = editingDeviceId != null, typeChosen = editingDeviceId != null),
    )
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    // Instantané initial pour détecter les modifications non enregistrées.
    private var initialName = ""
    private var initialIp = ""
    private var initialType = DeviceType.PLUG
    private var initialIpName: String? = null
    private var initialIp2Address: String? = null
    private var initialIp2Name: String? = null

    // Capacités détectées par la dernière sonde réussie, persistées à l'ajout.
    private var capabilities: DeviceCapabilities? = null

    // IP pour laquelle [capabilities]/[AddEditUiState.foundDeviceName] sont valides — invalidés
    // dès que l'utilisateur retape une IP différente (voir onIpChange).
    private var probedIp: String? = null

    // Sonde automatique en cours (débounce après saisie de l'IP, ou déclenchée par la touche
    // Suivant/OK du clavier) — annulée/relancée à chaque frappe, jointe si l'ajout est confirmé
    // avant qu'elle ait fini (voir testAndAdd).
    private var probeJob: Job? = null

    // Tous les canaux du même appareil physique (même IP), y compris celui édité — un seul élément
    // hors mode groupé. Utilisé pour appliquer nom/IP/type à l'ensemble à l'enregistrement.
    private var groupMembers: List<Device> = emptyList()

    // Nom du composant LED détecté par le dernier getLedState() réussi — nécessaire pour écrire
    // avec setLedState() sans re-détecter (nom et casse varient selon le modèle, voir DeviceRepository).
    private var ledComponent: String? = null

    // Appareil édité tel que lu en base (épingle à jour) — sert à la vérification/confirmation/oubli du certificat.
    private var observedDevice: Device? = null

    // Déclencheur de relecture du coffre : retirer un mot de passe ACCEPTÉ ne change ni les problèmes
    // d'accès ni l'appareil en base, donc rien d'autre ne ferait réémettre le combine ci-dessous.
    private val passwordRefresh = MutableStateFlow(0)

    init {
        editingDeviceId?.let { id ->
            viewModelScope.launch {
                combine(repository.observeDevice(id), repository.accessIssues, passwordRefresh) { device, issues, _ -> device to issues }
                    .collect { (device, issues) ->
                        observedDevice = device
                        // Le coffre est relu (sans réseau) à chaque émission : changement de problème
                        // d'accès, d'appareil, ou déclencheur [passwordRefresh] après une action locale.
                        val wasHidden = _uiState.value.passwordSection == null
                        val passwordSection = device?.let { d ->
                            passwordSectionStateOf(repository.passwordStateOf(d), passwordIssueFor(d, issues))
                        }
                        if (wasHidden && passwordSection != null) {
                            logger.info(DiagnosticLogger.UI, "Section mot de passe affichée ($passwordSection)")
                        }
                        _uiState.update {
                            it.copy(
                                tlsState = device?.let { d -> tlsStateOf(d, issues) },
                                passwordSection = passwordSection,
                                passwordHttpNote = device != null && device.certPin == null,
                            )
                        }
                    }
            }
        }
        editingDeviceId?.let { id ->
            viewModelScope.launch {
                repository.getDevice(id)?.let { device ->
                    val siblings = repository.getDevicesOnce().filter { it.ipAddress == device.ipAddress && it.id != device.id }
                    groupMembers = listOf(device) + siblings
                    val isGroupEdit = siblings.isNotEmpty()
                    // Mode groupé : le champ Nom porte le nom de l'appareil physique (partagé),
                    // jamais le nom d'un canal individuel — celui-ci se modifie ailleurs (Réglages).
                    val editedName = if (isGroupEdit) device.deviceName else device.name
                    initialName = editedName
                    initialIp = device.ipAddress
                    initialType = device.type
                    initialIpName = device.ipName
                    initialIp2Address = device.ip2Address
                    initialIp2Name = device.ip2Name
                    _uiState.update {
                        it.copy(
                            isGroupEdit = isGroupEdit,
                            name = editedName, ipAddress = device.ipAddress, type = device.type,
                            ipName = device.ipName, ip2Address = device.ip2Address, ip2Name = device.ip2Name,
                            driver = device.driver,
                        )
                    }
                    val (component, ledState) = repository.getLedState(groupMembers)
                    ledComponent = component
                    _uiState.update { it.copy(ledState = ledState) }

                    val cloudInfo = repository.getCloudInfo(device)
                    _uiState.update { it.copy(cloudInfo = cloudInfo) }
                    (cloudInfo as? CloudInfo.Available)?.macId?.let { repository.cacheCloudId(groupMembers, it) }

                    // Arrivée depuis le bandeau « Maj dispo » du Tableau (2026-09-23) : le détail
                    // exact (versions) n'est connu qu'ici, pas au moment de la vérification
                    // automatique qui a juste posé le drapeau — revérifie tout de suite plutôt que
                    // de laisser l'utilisateur retaper Vérifier pour une info qu'on vient de lui
                    // annoncer.
                    if (scrollToFirmwareOnLoad) checkFirmwareUpdate()
                }
            }
        }
    }

    /** Bascule la LED — relit l'état réel ensuite plutôt que de le supposer, en cas d'échec compris. */
    fun onToggleLed(enabled: Boolean) {
        val component = ledComponent ?: return
        viewModelScope.launch {
            repository.setLedState(groupMembers, component, enabled)
            val (refreshedComponent, ledState) = repository.getLedState(groupMembers)
            ledComponent = refreshedComponent
            _uiState.update { it.copy(ledState = ledState) }
        }
    }

    // --- Mise à jour et redémarrage firmware — par appareil physique (2026-08-19), un seul
    // canal du groupe suffit (même firmware, même redémarrage pour tous). Anciennement dupliqué
    // à l'identique sur l'écran Détail de chaque canal.

    /** Vérification manuelle (seul appel du projet qui sort du réseau local). */
    fun checkFirmwareUpdate() {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(firmwareChecking = true, firmwareInstallMessage = null) }
            val result = repository.checkFirmwareUpdate(dev)
            _uiState.update { it.copy(firmwareChecking = false, firmwareCheck = result) }
        }
    }

    /** Démarre l'installation de la mise à jour stable ; l'appareil redémarre pour l'appliquer. */
    fun installFirmwareUpdate() {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(firmwareInstalling = true) }
            val result = repository.installFirmwareUpdate(dev)
            _uiState.update {
                it.copy(
                    firmwareInstalling = false,
                    // Redémarrage à venir : l'état vérifié devient obsolète.
                    firmwareCheck = if (result is RpcResult.Success) null else it.firmwareCheck,
                    firmwareInstallMessage = if (result is RpcResult.Success) {
                        UserMessage(R.string.firmware_install_started)
                    } else {
                        UserMessage(R.string.firmware_install_error)
                    },
                )
            }
        }
    }

    fun consumeFirmwareInstallMessage() {
        _uiState.update { it.copy(firmwareInstallMessage = null) }
    }

    /** Redémarre l'appareil (dépannage) ; jamais bloqué par un minuteur en cours. */
    fun rebootDevice() {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(rebooting = true) }
            val result = repository.rebootDevice(dev)
            _uiState.update {
                it.copy(
                    rebooting = false,
                    firmwareCheck = if (result is RpcResult.Success) null else it.firmwareCheck,
                    rebootMessage = if (result is RpcResult.Success) {
                        UserMessage(R.string.firmware_reboot_started)
                    } else {
                        UserMessage(R.string.firmware_reboot_error)
                    },
                )
            }
        }
    }

    fun consumeRebootMessage() {
        _uiState.update { it.copy(rebootMessage = null) }
    }

    // --- Cloud Shelly — par appareil physique, comme la LED et le firmware ci-dessus. Toujours
    // à la demande explicite de l'utilisateur, jamais activé par Hestia de sa propre initiative.

    fun onToggleCloud(enabled: Boolean) {
        val dev = groupMembers.firstOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(cloudToggling = true) }
            repository.setCloudEnabled(dev, enabled)
            val cloudInfo = repository.getCloudInfo(dev)
            _uiState.update { it.copy(cloudToggling = false, cloudInfo = cloudInfo) }
            (cloudInfo as? CloudInfo.Available)?.macId?.let { repository.cacheCloudId(groupMembers, it) }
        }
    }

    fun onNameChange(value: String) = _uiState.update {
        val next = it.copy(name = value, nameError = null, error = null)
        next.copy(isDirty = isDirty(next))
    }

    fun onIpChange(value: String) {
        probeJob?.cancel()
        // Changement d'IP : la confiance provisoire accordée à l'ancienne adresse n'a plus de sens.
        repository.forgetProvisionalCertificate(_uiState.value.ipAddress.trim())
        repository.forgetProvisionalPassword(_uiState.value.ipAddress.trim())
        _uiState.update {
            val next = it.copy(
                ipAddress = value, ipError = null, error = null,
                foundDeviceName = null, autoProbing = false, certificatePrompt = null,
                passwordPrompt = false, passwordInput = "",
            )
            next.copy(isDirty = isDirty(next))
        }
        // Sonde auto (prise, ajout uniquement) : débounce, jamais sur chaque frappe brute — voir
        // aussi onIpImeAction pour un déclenchement immédiat via le clavier (retour David,
        // 2026-09-23 : compter sur la perte de focus n'est pas fiable, rien ne garantit que
        // l'utilisateur tape ailleurs à l'écran). Purement opportuniste, jamais de demande de
        // permission ici (CLAUDE.md : jamais de popup surprise pendant une simple saisie) — si la
        // permission réseau local manque encore, on se tait, le tap explicite sur Ajouter reste le
        // seul déclencheur de la demande, comme avant ce lot (voir onPrimaryAction côté écran).
        val state = _uiState.value
        if (!state.isEditMode && state.type.usesProbe() && isValidIpv4(value) && LocalNetworkPermission.isUsable(context)) {
            probeJob = viewModelScope.launch {
                delay(AUTO_PROBE_DEBOUNCE_MS)
                runAutoProbe(value)
            }
        }
    }

    /** Touche Suivant/OK du clavier sur le champ IP : sonde immédiate, sans attendre le débounce. */
    fun onIpImeAction() {
        val state = _uiState.value
        if (state.isEditMode || !state.type.usesProbe()) return
        val ip = state.ipAddress
        if (!isValidIpv4(ip) || !LocalNetworkPermission.isUsable(context)) return
        probeJob?.cancel()
        probeJob = viewModelScope.launch { runAutoProbe(ip) }
    }

    private suspend fun runAutoProbe(ip: String) {
        _uiState.update { it.copy(autoProbing = true) }
        when (val result = repository.probe(ip)) {
            is RpcResult.Success -> {
                capabilities = result.value
                probedIp = ip
                val found = result.value.reportedName?.takeIf { it.isNotBlank() }
                _uiState.update {
                    // Le nom trouvé alimente aussi `name` (champ alors masqué à l'écran) : le
                    // reste du parcours (addChannels, validateFields…) continue de lire ce champ
                    // sans rien savoir de la sonde automatique.
                    it.copy(autoProbing = false, foundDeviceName = found, name = found ?: it.name, passwordPrompt = false)
                }
            }
            is RpcResult.Failure -> {
                capabilities = null
                probedIp = null
                if (result.kind == RpcFailure.HTTPS_REQUIRED) {
                    val peekError = promptForCertificate(ip)
                    _uiState.update { it.copy(autoProbing = false, foundDeviceName = null, error = peekError) }
                } else if (result.kind == RpcFailure.PASSWORD_REQUIRED || result.kind == RpcFailure.PASSWORD_WRONG) {
                    val message = promptForPassword(ip, result.kind)
                    _uiState.update { it.copy(autoProbing = false, foundDeviceName = null, error = message) }
                } else {
                    _uiState.update { it.copy(autoProbing = false, foundDeviceName = null) }
                }
            }
            else -> {
                capabilities = null
                probedIp = null
                _uiState.update { it.copy(autoProbing = false, foundDeviceName = null) }
            }
        }
    }

    /**
     * Appareil HTTPS-only : lit son certificat et ouvre le dialogue de confiance. Renvoie l'erreur à
     * afficher si la lecture échoue (null = dialogue ouvert). Rien n'est épinglé avant le tap sur
     * Faire confiance ([onTrustCertificate]).
     */
    private suspend fun promptForCertificate(ip: String): UserMessage? =
        when (val peek = repository.peekCertificate(ip)) {
            is RpcResult.Success -> {
                logger.info(DiagnosticLogger.UI, "Dialogue de certificat affiché ($ip, ${peek.value.fingerprint})")
                _uiState.update {
                    it.copy(certificatePrompt = CertificatePrompt(ip, peek.value, changed = false))
                }
                null
            }
            else -> {
                logger.warn(DiagnosticLogger.UI, "Lecture du certificat impossible ($ip)")
                UserMessage(R.string.tls_peek_failed)
            }
        }

    /**
     * Appareil protégé par mot de passe : affiche le champ (jamais tant qu'un dialogue de certificat
     * est ouvert, le certificat passe d'abord) et renvoie l'erreur à montrer. En cas de refus, le
     * champ est conservé tel quel pour correction.
     */
    private fun promptForPassword(ip: String, kind: RpcFailure): UserMessage {
        if (kind == RpcFailure.PASSWORD_REQUIRED) {
            logger.info(DiagnosticLogger.UI, "Mot de passe demandé ($ip)")
        } else {
            logger.warn(DiagnosticLogger.UI, "Mot de passe refusé par l'appareil ($ip)")
        }
        _uiState.update { it.copy(passwordPrompt = it.certificatePrompt == null) }
        return UserMessage(
            if (kind == RpcFailure.PASSWORD_REQUIRED) R.string.error_password_required else R.string.error_password_wrong,
        )
    }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(passwordInput = value, error = null) }

    /** Confie le mot de passe saisi au registre (provisoire), vide le champ et relance la sonde. */
    fun onSubmitPassword() {
        val state = _uiState.value
        val input = state.passwordInput
        if (input.isEmpty()) return
        val ip = state.ipAddress.trim()
        logger.info(DiagnosticLogger.UI, "Mot de passe saisi ($ip)")
        repository.trustPasswordProvisionally(ip, input)
        _uiState.update { it.copy(passwordInput = "", error = null) }
        probeJob?.cancel()
        probeJob = viewModelScope.launch { runAutoProbe(ip) }
    }

    /** Accepte le certificat affiché (confiance provisoire, validée à l'ajout) et relance la sonde. */
    fun onTrustCertificate() {
        val prompt = _uiState.value.certificatePrompt ?: return
        logger.info(DiagnosticLogger.UI, "Certificat accepté (${prompt.ip})")
        val editing = observedDevice
        if (_uiState.value.isEditMode && editing != null) {
            // Édition : l'épingle est écrite tout de suite sur tous les canaux de l'appareil.
            _uiState.update { it.copy(certificatePrompt = null, error = null) }
            viewModelScope.launch {
                repository.trustCertificate(editing, prompt.certificate.pin)
                passwordRefresh.update { it + 1 }
            }
            return
        }
        repository.trustCertificateProvisionally(prompt.ip, prompt.certificate.pin)
        _uiState.update { it.copy(certificatePrompt = null, error = null) }
        probeJob?.cancel()
        probeJob = viewModelScope.launch { runAutoProbe(prompt.ip) }
    }

    /** Refuse le certificat : rien n'est enregistré, l'erreur explique la marche à suivre. */
    fun onDismissCertificate() {
        val prompt = _uiState.value.certificatePrompt ?: return
        logger.info(DiagnosticLogger.UI, "Certificat refusé (${prompt.ip})")
        // En édition, refuser ne change rien : pas d'erreur (l'état reste celui affiché par la section).
        _uiState.update {
            it.copy(
                certificatePrompt = null,
                error = if (it.isEditMode) null else UserMessage(R.string.error_https_required),
            )
        }
    }

    /** Mode édition : relit le certificat de l'appareil et ouvre le dialogue de confirmation. */
    fun checkCertificate() {
        val device = observedDevice ?: return
        logger.info(DiagnosticLogger.UI, "Vérification du certificat demandée (${device.ipAddress})")
        viewModelScope.launch {
            when (val peek = repository.peekCertificate(device.ipAddress)) {
                is RpcResult.Success -> {
                    val changed = device.certPin != null && peek.value.pin != device.certPin
                    logger.info(
                        DiagnosticLogger.UI,
                        "Certificat lu (${device.ipAddress}, ${peek.value.fingerprint}, modifié=$changed)",
                    )
                    _uiState.update {
                        it.copy(
                            certificatePrompt = CertificatePrompt(
                                device.ipAddress,
                                peek.value,
                                changed,
                                passwordWillBeSuspended = changed && repository.passwordStateOf(device) != null,
                            ),
                        )
                    }
                }
                else -> {
                    logger.warn(DiagnosticLogger.UI, "Lecture du certificat impossible (${device.ipAddress})")
                    _uiState.update { it.copy(error = UserMessage(R.string.tls_peek_failed)) }
                }
            }
        }
    }

    /** Mode édition : oublie l'épingle (après confirmation) ; l'appareil repasse en HTTP simple. */
    fun forgetCertificate() {
        val device = observedDevice ?: return
        logger.info(DiagnosticLogger.UI, "Certificat oublié par l'utilisateur (${device.ipAddress})")
        viewModelScope.launch { repository.forgetCertificate(device) }
    }

    /** Mode édition : enregistre le mot de passe saisi (chiffré, jamais journalisé) puis le vérifie. */
    fun savePassword(value: String) {
        val device = observedDevice ?: return
        if (value.isEmpty()) return
        logger.info(DiagnosticLogger.UI, "Mot de passe enregistré depuis Modifier (${device.ipAddress})")
        viewModelScope.launch {
            repository.savePassword(device, value)
            passwordRefresh.update { it + 1 }
        }
    }

    /** Mode édition : retire le mot de passe enregistré (après confirmation). */
    fun removePassword() {
        val device = observedDevice ?: return
        logger.info(DiagnosticLogger.UI, "Mot de passe retiré depuis Modifier (${device.ipAddress})")
        viewModelScope.launch {
            repository.removePassword(device)
            passwordRefresh.update { it + 1 }
        }
    }

    override fun onCleared() {
        repository.forgetProvisionalCertificate(_uiState.value.ipAddress.trim())
        repository.forgetProvisionalPassword(_uiState.value.ipAddress.trim())
        super.onCleared()
    }

    /**
     * Mode ajout uniquement : choix (ou changement d'avis) Prise/Variateur/Détecteur via les trois tuiles,
     * toujours visibles — celle non retenue se grise plutôt que de disparaître (retour David,
     * 2026-09-23). Un vrai changement de type efface tout ce que la sonde automatique avait pu
     * trouver pour l'autre type (n'a plus de sens), et relance tout de suite une sonde fraîche si
     * on bascule vers Prise ou Variateur avec une IP déjà valide — comme si elle venait d'être tapée.
     */
    fun onTypeChosen(value: DeviceType) {
        val current = _uiState.value
        val switching = current.typeChosen && current.type != value
        if (switching) {
            probeJob?.cancel()
            capabilities = null
            probedIp = null
            // Le champ mot de passe n'a plus de sens après un changement de type (D4).
            repository.forgetProvisionalPassword(current.ipAddress.trim())
        }
        _uiState.update {
            val next = it.copy(
                passwordPrompt = if (switching) false else it.passwordPrompt,
                passwordInput = if (switching) "" else it.passwordInput,
                type = value,
                typeChosen = true,
                foundDeviceName = if (switching) null else it.foundDeviceName,
                autoProbing = if (switching) false else it.autoProbing,
                error = null, nameError = null, ipError = null,
            )
            next.copy(isDirty = isDirty(next))
        }
        val ip = _uiState.value.ipAddress
        if (switching && value.usesProbe() && isValidIpv4(ip) && LocalNetworkPermission.isUsable(context)) {
            probeJob = viewModelScope.launch {
                delay(AUTO_PROBE_DEBOUNCE_MS)
                runAutoProbe(ip)
            }
        }
    }

    /** Modifie (ou nomme pour la première fois) le 1ᵉʳ emplacement IP. Toujours présent. */
    fun onEditIpSlot1(name: String, ip: String) = _uiState.update {
        val next = it.copy(ipAddress = ip, ipName = name.ifBlank { null }, ipError = null, error = null)
        next.copy(isDirty = isDirty(next))
    }

    /** Ajoute ou modifie le 2ᵉ emplacement IP optionnel. */
    fun onEditIpSlot2(name: String, ip: String) = _uiState.update {
        val next = it.copy(ip2Address = ip, ip2Name = name.ifBlank { null })
        next.copy(isDirty = isDirty(next))
    }

    /** Supprime le 2ᵉ emplacement IP. Le 1ᵉʳ reste toujours présent (appareil jamais sans IP). */
    fun onDeleteIpSlot2() = _uiState.update {
        val next = it.copy(ip2Address = null, ip2Name = null)
        next.copy(isDirty = isDirty(next))
    }

    private fun isDirty(state: AddEditUiState): Boolean =
        // Mode ajout uniquement : `initialType` vaut toujours PLUG par défaut (rien à comparer
        // avant un vrai choix), donc choisir la tuile Prise ne changeait jamais `state.type` par
        // rapport à ce défaut — contrairement à Détecteur de fumée, dirty immédiatement pour la
        // même action. Un choix de type fait via les tuiles compte comme une vraie saisie dans les
        // deux cas (retour David, 2026-09-23 : pas de popup de confirmation en quittant après avoir
        // choisi Prise, alors qu'il y en avait un pour Détecteur).
        (!state.isEditMode && state.typeChosen) ||
            state.name != initialName || state.ipAddress != initialIp || state.type != initialType ||
            state.ipName != initialIpName || state.ip2Address != initialIp2Address || state.ip2Name != initialIp2Name

    /** Valide les champs (public pour gater la demande de permission avant tout contact réseau). */
    fun validate(): Boolean = validateFields()

    /** Valide les champs. Renvoie true si valides (et pose les erreurs sinon). */
    private fun validateFields(): Boolean {
        val state = _uiState.value
        val nameError = if (state.name.isBlank()) UserMessage(R.string.error_name_required) else null
        val ipError = when {
            state.ipAddress.isBlank() -> UserMessage(R.string.error_ip_required)
            !isValidIpv4(state.ipAddress) -> UserMessage(R.string.error_ip_invalid)
            else -> null
        }
        _uiState.update { it.copy(nameError = nameError, ipError = ipError) }
        return nameError == null && ipError == null
    }

    /**
     * Mode ajout : teste la connexion (Shelly.GetDeviceInfo + GetComponents), rejette les Gen1,
     * puis ajoute directement si un seul canal, ou propose la sélection si plusieurs.
     * L'appelant doit s'être assuré que la permission réseau local est utilisable.
     *
     * Réutilise le résultat de la sonde automatique déjà lancée pour cette IP si elle est encore
     * valide ([probedIp]) — jamais un second appel RPC redondant juste parce que l'utilisateur a
     * tapé Ajouter (retour David, 2026-09-23, lot « sonde auto sur l'IP »). Si une sonde est
     * encore en vol (débounce pas encore écoulé), on l'attend plutôt que d'en lancer une autre.
     */
    fun testAndAdd() {
        if (!validateFields()) return
        val ip = _uiState.value.ipAddress.trim()
        viewModelScope.launch {
            probeJob?.join()
            val caps = capabilities.takeIf { probedIp == ip } ?: run {
                _uiState.update { it.copy(isTesting = true, error = null) }
                when (val result = repository.probe(ip)) {
                    is RpcResult.Success -> result.value.also { capabilities = it; probedIp = ip }
                    is RpcResult.Failure -> {
                        val message = if (result.kind == RpcFailure.HTTPS_REQUIRED) {
                            promptForCertificate(ip)
                        } else if (result.kind == RpcFailure.PASSWORD_REQUIRED || result.kind == RpcFailure.PASSWORD_WRONG) {
                            promptForPassword(ip, result.kind)
                        } else {
                            result.toUserMessageOrNull()
                        }
                        _uiState.update { it.copy(isTesting = false, error = message) }
                        return@launch
                    }
                    else -> {
                        _uiState.update { it.copy(isTesting = false, error = result.toUserMessageOrNull()) }
                        return@launch
                    }
                }
            }
            // Ni relais, ni light, ni volet (capteur…) : refus expliqué plutôt qu'un canal 0
            // supposé dont toutes les commandes échoueraient ensuite.
            caps.unsupportedKind?.let { kind ->
                _uiState.update { it.copy(isTesting = false, error = kind.toUserMessage()) }
                return@launch
            }
            // Variateur et/ou volet sans relais (light:N, cover:N) : tous les canaux ajoutés d'un coup,
            // sans écran de sélection en v1.
            if (caps.hasNoSwitchChannels()) {
                val added = lightRepository.addLightChannels(_uiState.value.name.trim(), ip, caps) +
                    coverRepository.addCoverChannels(_uiState.value.name.trim(), ip, caps)
                if (added == 0) {
                    _uiState.update { it.copy(isTesting = false, error = UserMessage(R.string.error_device_exists)) }
                } else {
                    repository.commitProvisionalAccess(ip)
                    repository.resyncSmokeRelay()
                    confirmThenClose(caps.reportedName?.takeIf { it.isNotBlank() } ?: _uiState.value.name.trim())
                }
                return@launch
            }
            _uiState.update { it.copy(isTesting = true, error = null) }
            val channels = caps.switchChannels
            if (channels.size == 1) {
                addChannels(channels)
            } else {
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        channelSelection = ChannelSelection(
                            model = caps.model,
                            channels = channels,
                            selected = channels.toSet(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Mode ajout d'un détecteur de fumée : pas de sonde complète, contrairement à [testAndAdd] —
     * voir SMOKE-DETECTOR.md et [DeviceRepository.addSmokeDetector]. L'écran a demandé à
     * l'utilisateur de réveiller l'appareil juste avant (3 appuis brefs) : cet appel est
     * synchrone et peut prendre jusqu'à quelques secondes (timeout RPC), d'où le spinner
     * ([AddEditUiState.isTesting], comme pour une prise).
     */
    fun addSmokeDetector() {
        if (!validateFields()) return
        val state = _uiState.value
        _uiState.update { it.copy(isTesting = true, error = null) }
        viewModelScope.launch {
            val resolvedName = repository.addSmokeDetector(state.name.trim(), state.ipAddress.trim())
            if (resolvedName != null) {
                // Un appareil scriptable a peut-être de la place pour relayer ses alertes ntfy
                // dès maintenant (voir SMOKE-DETECTOR.md § Lot 4a) — best-effort, silencieux.
                repository.resyncSmokeRelay()
                confirmThenClose(resolvedName)
            } else {
                _uiState.update { it.copy(isTesting = false, error = UserMessage(R.string.error_device_exists)) }
            }
        }
    }

    /**
     * Affiche le nom réellement retenu quelques secondes avant de fermer l'écran (retour David,
     * 2026-09-23) — rassure que l'appareil a bien été contacté et lu, pas seulement le texte tapé.
     * Partagé par [addSmokeDetector] et [addChannels] (prise, seule ou multi-canaux).
     */
    private suspend fun confirmThenClose(name: String) {
        _uiState.update { it.copy(isTesting = false, confirmedDeviceName = name) }
        delay(CONFIRMATION_DELAY_MS)
        _uiState.update { it.copy(done = true) }
    }

    fun toggleChannel(channelId: Int) = _uiState.update { state ->
        val selection = state.channelSelection ?: return@update state
        val newSelected = if (channelId in selection.selected) {
            selection.selected - channelId
        } else {
            selection.selected + channelId
        }
        state.copy(channelSelection = selection.copy(selected = newSelected))
    }

    fun confirmChannelSelection() {
        val state = _uiState.value
        val selection = state.channelSelection ?: return
        if (selection.selected.isEmpty()) {
            _uiState.update { it.copy(error = UserMessage(R.string.error_channel_required)) }
            return
        }
        _uiState.update { it.copy(isTesting = true, error = null) }
        viewModelScope.launch {
            addChannels(selection.selected.sorted())
        }
    }

    private suspend fun addChannels(channels: List<Int>) {
        val caps = capabilities ?: return
        val state = _uiState.value
        val added = repository.addChannels(
            name = state.name.trim(),
            ip = state.ipAddress.trim(),
            // Un relais ajouté via la tuile Variateur ou Volet reste une prise : la tuile choisie ne
            // change pas le résultat (spec § 2) — LAMP est réservé aux canaux light.
            type = if (state.type == DeviceType.LAMP || state.type == DeviceType.SHUTTER) DeviceType.PLUG else state.type,
            capabilities = caps,
            switchIds = channels,
        )
        val addedLights = if (caps.lightChannels.isNotEmpty()) {
            lightRepository.addLightChannels(state.name.trim(), state.ipAddress.trim(), caps)
        } else {
            0
        }
        val addedCovers = if (caps.coverChannels.isNotEmpty()) {
            coverRepository.addCoverChannels(state.name.trim(), state.ipAddress.trim(), caps)
        } else {
            0
        }
        if (added + addedLights + addedCovers == 0) {
            _uiState.update {
                it.copy(isTesting = false, channelSelection = null, error = UserMessage(R.string.error_device_exists))
            }
        } else {
            // Un nouvel appareil scriptable a peut-être de la place pour relayer les alertes ntfy
            // des détecteurs de fumée — sans cet appel, rien ne le découvrait avant le prochain
            // changement de réglage ntfy (retour David, 2026-09-17 : « j'ai ajouté une prise, elle
            // n'a pas le picto relais, comment l'avoir ? »). Best-effort, silencieux si non
            // pertinent (pas de détecteur, ou déjà de la place ailleurs — voir resyncSmokeRelay).
            repository.commitProvisionalAccess(state.ipAddress.trim())
            repository.resyncSmokeRelay()
            val resolvedName = caps.reportedName?.takeIf { it.isNotBlank() } ?: state.name.trim()
            confirmThenClose(resolvedName)
        }
    }

    /** Mode modification : enregistre les champs (nom, IP, type) sans réinterroger l'appareil. */
    fun saveEdit() {
        if (!validateFields()) return
        val id = editingDeviceId ?: return
        viewModelScope.launch {
            val existing = repository.getDevice(id) ?: return@launch
            val state = _uiState.value
            if (state.isGroupEdit) {
                // Champs partagés (nom d'appareil, IP, type) appliqués à tous les canaux d'un
                // coup — jamais le nom de canal individuel, laissé tel quel sur chacun.
                val deviceNameChanged = state.name.trim() != initialName
                val outcome = runCatching {
                    repository.updateDeviceGroup(
                        members = groupMembers,
                        deviceName = state.name.trim(),
                        ipAddress = state.ipAddress.trim(),
                        ip2Address = state.ip2Address,
                        ipName = state.ipName,
                        ip2Name = state.ip2Name,
                        type = state.type,
                    )
                }
                if (outcome.isFailure) {
                    _uiState.update { it.copy(error = UserMessage(R.string.error_device_exists)) }
                } else {
                    // Nom des prises (2026-09-07) : pousse le nouveau nom partagé sur l'appareil
                    // physique lui-même (Sys.SetConfig), best-effort — jamais le nom de canal
                    // individuel, laissé tel quel (cf. commentaire ci-dessus).
                    if (deviceNameChanged) repository.pushPhysicalDeviceName(groupMembers, state.name.trim())
                    _uiState.update { it.copy(done = true) }
                }
                return@launch
            }
            val nameChanged = state.name.trim() != existing.name
            val updated = existing.copy(
                name = state.name.trim(),
                deviceName = state.name.trim(),
                ipAddress = state.ipAddress.trim(),
                type = state.type,
                ipName = state.ipName,
                ip2Address = state.ip2Address,
                ip2Name = state.ip2Name,
            )
            val outcome = runCatching { repository.updateDevice(updated) }
            if (outcome.isFailure) {
                // Violation de la contrainte d'unicité (ipAddress, switchId).
                _uiState.update { it.copy(error = UserMessage(R.string.error_device_exists)) }
            } else {
                // Le nom est écrit en dur dans les scripts déployés (notifs ntfy) : les redéployer
                // avec le nouveau nom si besoin. Lancé par le dépôt sur sa propre portée (pas
                // viewModelScope) : l'écran se ferme aussitôt après (voir onDone ci-dessous), ce
                // qui tuerait la resynchro avant la fin si elle dépendait de ce ViewModel.
                // Appareil mono-canal : nom de canal et nom d'appareil physique sont poussés tous
                // les deux (ils sont toujours identiques pour ce cas, voir Device.deviceName),
                // dans le même appel pour n'alimenter qu'un seul indicateur de rattrapage.
                if (nameChanged) repository.resyncDeviceName(updated, alsoPhysicalName = updated.deviceName)
                _uiState.update { it.copy(done = true) }
            }
        }
    }

    private companion object {
        /** Pause avant de relancer la sonde après la dernière frappe dans le champ IP — voir
         * [onIpChange]. Assez court pour rester réactif, assez long pour ne pas sonder à chaque
         * caractère tapé. */
        const val AUTO_PROBE_DEBOUNCE_MS = 800L

        /** Durée d'affichage du nom retenu avant fermeture automatique de l'écran, voir
         * [confirmThenClose]. */
        const val CONFIRMATION_DELAY_MS = 3_000L
    }
}

/** Prise / relais et Variateur partagent la même sonde : ce sont les composants qui décident. */
internal fun DeviceType.usesProbe(): Boolean = this == DeviceType.PLUG || this == DeviceType.LAMP || this == DeviceType.SHUTTER
