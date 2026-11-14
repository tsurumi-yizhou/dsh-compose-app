package dev.fughetta.dshcompose.ui.runtime

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

enum class EnginePage {
    Overview,
    Setup,
    Logs,
    Diagnostics,
}

enum class SetupStep {
    Welcome,
    Choice,
    Appearance,
    Access,
    Connect,
    Finishing,
    Finished,
}

data class EngineHazard(val path: String, val detail: String)

data class EngineLogLine(val sequence: Long, val text: String, val stream: String)

data class EngineRestorePlan(
    val available: Boolean = false,
    val at: Long? = null,
    val disable: PersistentList<String> = persistentListOf(),
    val enable: PersistentList<String> = persistentListOf(),
    val changed: PersistentList<String> = persistentListOf(),
    val patchChanged: Boolean = false,
)

data class EngineControlsState(
    val page: EnginePage = EnginePage.Overview,
    val setup: SetupStep = SetupStep.Welcome,
    val waitingForConnection: Boolean = false,
    val onboardingComplete: Boolean = false,
    val localDeclined: Boolean = false,
    val phase: String = "notInstalled",
    val mode: String = "normal",
    val error: String? = null,
    val failure: String? = null,
    val failureKind: String? = null,
    val failureExit: Int? = null,
    val failureRequired: PersistentList<String> = persistentListOf(),
    val failureSuspects: PersistentList<String> = persistentListOf(),
    val bundledVersion: String? = null,
    val installedVersion: String? = null,
    val installStep: String? = null,
    val installDone: Long = 0,
    val installTotal: Long = 0,
    val memoryBytes: Long = 0,
    val freeBytes: Long = 0,
    val requiredBytes: Long = 0,
    val executionMode: String? = null,
    val busy: Boolean = false,
    val operation: String? = null,
    val confirmation: String? = null,
    val keepUiAndSkills: Boolean = true,
    val keepPluginsAndSettings: Boolean = false,
    val hazards: PersistentList<EngineHazard> = persistentListOf(),
    val restore: EngineRestorePlan = EngineRestorePlan(),
    val logs: PersistentList<EngineLogLine> = persistentListOf(),
    val followLogs: Boolean = true,
    val diagnosticsPreview: String? = null,
    val diagnosticsRevision: Long = 0,
    val diagnosticsReviewed: Boolean = false,
    val diagnosticsResult: String? = null,
)

sealed interface EngineControlsAction {
    data class Show(val page: EnginePage) : EngineControlsAction

    data object Refresh : EngineControlsAction

    data object Welcome : EngineControlsAction

    data object ChooseLocal : EngineControlsAction

    data object ChooseRemote : EngineControlsAction

    data object ContinueSetup : EngineControlsAction

    data object SkipProvider : EngineControlsAction

    data object OpenProviders : EngineControlsAction

    data object OpenAccount : EngineControlsAction

    data object OpenAppearance : EngineControlsAction

    data object OpenPhoneAccess : EngineControlsAction

    data object Install : EngineControlsAction

    data object Start : EngineControlsAction

    data object Stop : EngineControlsAction

    data object Restart : EngineControlsAction

    data object Restore : EngineControlsAction

    data object Reset : EngineControlsAction

    data object Uninstall : EngineControlsAction

    data object DropKeptAndInstall : EngineControlsAction

    data object Confirm : EngineControlsAction

    data object DismissConfirmation : EngineControlsAction

    data object ClearFailure : EngineControlsAction

    data class ResetKeep(val uiAndSkills: Boolean, val pluginsAndSettings: Boolean) :
        EngineControlsAction

    data class FollowLogs(val enabled: Boolean) : EngineControlsAction

    data object CollectDiagnostics : EngineControlsAction

    data class ReviewDiagnostics(val revision: Long, val reviewed: Boolean) : EngineControlsAction

    data object ShareDiagnostics : EngineControlsAction

    data object SaveDiagnostics : EngineControlsAction

    data object PublishDiagnostics : EngineControlsAction
}

/** Why the chat shows its one line about background running. */
enum class BackgroundNetReason {
    /** Android reported this app's network blocked while it was in the background. */
    Blocked,

    /** The engine's model requests could not get through while the app was in the background. */
    Failures,

    /** After the first finished turn, on a phone whose maker holds background apps back: once. */
    Intro,
}
