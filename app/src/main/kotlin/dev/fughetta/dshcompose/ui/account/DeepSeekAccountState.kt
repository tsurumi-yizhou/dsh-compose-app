package dev.fughetta.dshcompose.ui.account

import dev.fughetta.dshcompose.domain.account.AccountSignInAttempt
import dev.fughetta.dshcompose.domain.account.DeepSeekAccountBalance
import dev.fughetta.dshcompose.domain.account.DeepSeekAccountProfile

enum class DeepSeekAccountPhase {
    Unknown,
    SignedOut,
    SigningIn,
    SignedIn,
}

data class DeepSeekAccountState(
    val phase: DeepSeekAccountPhase = DeepSeekAccountPhase.Unknown,
    val connected: Boolean = false,
    val attempt: AccountSignInAttempt? = null,
    val profile: DeepSeekAccountProfile? = null,
    val balance: DeepSeekAccountBalance? = null,
    val usageUrl: String? = null,
    val topUpUrl: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val profileError: Boolean = false,
    val balanceError: Boolean = false,
    val browserReturned: Boolean = false,
    val confirmSignOut: Boolean = false,
    val runningAccountTasks: Boolean? = null,
    val identityConfirmed: Boolean = false,
)

sealed interface DeepSeekAccountAction {
    data object SignIn : DeepSeekAccountAction

    data object Cancel : DeepSeekAccountAction

    data object ReopenBrowser : DeepSeekAccountAction

    data object Refresh : DeepSeekAccountAction

    data object RequestSignOut : DeepSeekAccountAction

    data object ConfirmSignOut : DeepSeekAccountAction

    data object DismissSignOut : DeepSeekAccountAction

    data object Usage : DeepSeekAccountAction

    data object TopUp : DeepSeekAccountAction

    data object ApiKeys : DeepSeekAccountAction

    data class BrowserOpened(val success: Boolean) : DeepSeekAccountAction
}

sealed interface DeepSeekAccountEffect {
    data object StartLocalEngine : DeepSeekAccountEffect

    class Browser(val url: String, val generation: Any?) : DeepSeekAccountEffect {
        override fun toString() = "AccountBrowserRequest"
    }

    data object ApiKeys : DeepSeekAccountEffect

    data object IdentityChanged : DeepSeekAccountEffect
}
