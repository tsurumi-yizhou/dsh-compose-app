package dev.fughetta.dshcompose.ui.notify

/**
 * The second gate Samsung puts over live activities (Now Bar): whether this device has one, and
 * whether the system admits every app. Android's own promotion is
 * [NotificationSettingsState.liveAllowed]; only both together mean the Now Bar shows anything.
 */
enum class NowBarState {
    /** Not a device with a Now Bar: nothing to say about it. */
    NotApplicable,

    /** A Now Bar device whose switch could not be read. */
    Unknown,

    /** A Now Bar device that still keeps third-party apps out. */
    Blocked,

    /** A Now Bar device set to take live notifications from every app. */
    Allowed,
}

enum class NotificationPreference {
    Enabled,
    Done,
    Failed,
    Approval,
    Question,
    Live,
}

data class NotificationSettingsState(
    val enabled: Boolean = true,
    val done: Boolean = true,
    val failed: Boolean = true,
    val approval: Boolean = true,
    val question: Boolean = true,
    val live: Boolean = true,
    val systemEnabled: Boolean = false,
    val doneChannel: Boolean = false,
    val attentionChannel: Boolean = false,
    val liveSupported: Boolean = false,
    val liveAllowed: Boolean = false,
    val liveSettings: Boolean = false,
    val nowBar: NowBarState = NowBarState.NotApplicable,
    val nowBarWritable: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val permissionRequest: String? = null,
)

sealed interface NotificationSettingsAction {
    data object Refresh : NotificationSettingsAction

    data class Preference(val key: NotificationPreference, val enabled: Boolean) :
        NotificationSettingsAction

    data class OpenSystem(val channel: String? = null) : NotificationSettingsAction

    data object OpenLiveSystem : NotificationSettingsAction

    data object OpenNowBarSystem : NotificationSettingsAction

    data object EnableNowBar : NotificationSettingsAction

    data object RequestPermission : NotificationSettingsAction

    data class PermissionResult(val id: String?, val granted: Boolean) : NotificationSettingsAction
}

data class NotificationTarget(
    val host: String,
    val sessionId: String,
    val eventId: String? = null,
    val kind: String? = null,
)

data class NotificationBanner(
    val id: Long,
    val target: NotificationTarget,
    val title: String,
    val text: String,
    val attention: Boolean,
    val kind: String = "done",
)

/** Platform effects requested by the notification preferences page. */
sealed interface NotificationSettingsEffect {
    data object Refresh : NotificationSettingsEffect

    data class OpenSystem(val channel: String?) : NotificationSettingsEffect

    data object OpenLiveSystem : NotificationSettingsEffect

    data object OpenNowBarSystem : NotificationSettingsEffect

    data object EnableNowBar : NotificationSettingsEffect

    data object Disabled : NotificationSettingsEffect

    data object ChannelsChanged : NotificationSettingsEffect

    data class Error(val code: String) : NotificationSettingsEffect
}
