package dev.fughetta.dshcompose.ui.phone

import kotlinx.collections.immutable.*

enum class PhoneControlsPage {
    Overview,
    Access,
    Rules,
    Unlocks,
    Log,
    Background,
}

enum class PhoneAppRule {
    Allow,
    Confirm,
    Deny,
}

enum class PhoneAccessKind {
    Storage,
    AllFiles,
    Notifications,
    Accessibility,
    AppDetails,
    Battery,
    VendorBackground,
    Autostart,
    Popup,
    Overlay,
    WirelessDebugging,
}

enum class PhoneRiskKind {
    Disclosure,
    AlwaysConfirmOff,
    Unlock,
    AppRule,
    Storage,
    ResetRules,
    Root,
}

data class PhoneRisk(
    val kind: PhoneRiskKind,
    val packageName: String? = null,
    val rule: PhoneAppRule? = null,
    val unlock: String? = null,
)

data class PhonePlatformRequest(
    val id: String,
    val permissions: PersistentList<String>,
    val kind: PhoneAccessKind,
)

data class PhoneTaskView(
    val id: Long? = null,
    val active: Boolean = false,
    val app: String? = null,
    val paused: Boolean = false,
    val stopped: Boolean = false,
    val waived: Boolean = false,
)

data class PhoneAppRuleView(
    val packageName: String,
    val label: String,
    val rule: PhoneAppRule,
    val installed: Boolean = true,
)

data class PhoneLogView(
    val time: Long,
    val tool: String,
    val app: String?,
    val summary: String,
    val decision: String,
)

data class PhoneBackgroundView(
    val mode: String = "off",
    val ready: Boolean = false,
    val reason: String? = null,
    val supported: PersistentList<String> = persistentListOf("off"),
    val recommended: String = "off",
    val grantPath: String = "setup",
    val provider: String? = null,
    val providerRunning: Boolean = false,
    val providerGranted: Boolean = false,
    val adbPaired: Boolean = false,
    val adbConnected: Boolean = false,
    val wirelessDebugging: Boolean = false,
    val rootConnected: Boolean = false,
    val rootSelected: Boolean = false,
    val keep: Boolean = false,
    val keepPhase: String? = null,
    val keepReason: String? = null,
)

data class PhoneControlStatus(
    val enabled: Boolean = false,
    val bridgeListening: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
    val confirmationNotifications: Boolean = false,
    val keeperGranted: Boolean = false,
    val keeperCommand: String? = null,
    val restricted: String? = null,
    val sdk: Int = 0,
    val task: PhoneTaskView = PhoneTaskView(),
    val background: PhoneBackgroundView = PhoneBackgroundView(),
)

data class PhoneAccessView(
    val storage: Boolean = false,
    val notifications: Boolean = false,
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val battery: Boolean = false,
    val vendor: String? = null,
    val vendorVisited: PersistentSet<PhoneAccessKind> = persistentSetOf(),
)

data class PhoneControlsUiState(
    val revision: Long = 0,
    val page: PhoneControlsPage = PhoneControlsPage.Overview,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val status: PhoneControlStatus = PhoneControlStatus(),
    val confirmMode: String = "ask",
    val unlocks: PersistentMap<String, Boolean> = persistentMapOf(),
    val apps: PersistentList<PhoneAppRuleView> = persistentListOf(),
    val query: String = "",
    val log: PersistentList<PhoneLogView> = persistentListOf(),
    val access: PhoneAccessView = PhoneAccessView(),
    val phoneStorage: Boolean = false,
    val returnAfterDone: Boolean = true,
    val disclosureAccepted: Boolean = false,
    val screenText: String? = null,
    val pairingCode: String = "",
    val pairingPort: String = "",
    val platformRequest: PhonePlatformRequest? = null,
    val needsAllFiles: Boolean = false,
    val awaitingAccessibility: Boolean = false,
    val accessibilityAttemptFailed: Boolean = false,
    val risk: PhoneRisk? = null,
    val error: String? = null,
    val notice: String? = null,
)

sealed interface PhoneControlsAction {
    data class Observe(val observing: Boolean) : PhoneControlsAction

    data class Page(val page: PhoneControlsPage) : PhoneControlsAction

    data object Refresh : PhoneControlsAction

    data class Enabled(val enabled: Boolean) : PhoneControlsAction

    data class ConfirmMode(val mode: String) : PhoneControlsAction

    data class Unlock(val key: String, val enabled: Boolean) : PhoneControlsAction

    data class AppRule(val packageName: String, val rule: PhoneAppRule) : PhoneControlsAction

    data class Query(val text: String) : PhoneControlsAction

    data object AddPackage : PhoneControlsAction

    data object ResetRules : PhoneControlsAction

    data object ConfirmRisk : PhoneControlsAction

    data object CancelRisk : PhoneControlsAction

    data object TestScreen : PhoneControlsAction

    data object CloseScreenText : PhoneControlsAction

    data object StopTask : PhoneControlsAction

    data class Storage(val enabled: Boolean) : PhoneControlsAction

    data class ReturnAfterDone(val enabled: Boolean) : PhoneControlsAction

    data class OpenAccess(val kind: PhoneAccessKind) : PhoneControlsAction

    data class MarkVendorVisited(val kind: PhoneAccessKind, val visited: Boolean) :
        PhoneControlsAction

    data class PermissionResult(val id: String?, val granted: Boolean) : PhoneControlsAction

    data class BackgroundMode(val mode: String) : PhoneControlsAction

    data class BackgroundKeep(val enabled: Boolean) : PhoneControlsAction

    data object PrepareRoot : PhoneControlsAction

    data object RequestPrivilegedPermission : PhoneControlsAction

    data object GrantSecureSettings : PhoneControlsAction

    data object StartAdbPairing : PhoneControlsAction

    data class PairingCode(val code: String) : PhoneControlsAction

    data class PairingPortChanged(val port: String) : PhoneControlsAction

    data object PairAdb : PhoneControlsAction

    data object ConnectAdb : PhoneControlsAction
}
