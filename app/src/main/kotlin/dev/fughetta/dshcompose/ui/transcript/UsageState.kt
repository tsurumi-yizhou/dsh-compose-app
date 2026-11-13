package dev.fughetta.dshcompose.ui.transcript

import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.usage.SessionUsage
import dev.fughetta.dshcompose.domain.usage.UsageBalanceSource
import java.math.BigDecimal

data class UsageState(
    val hostKey: String? = null,
    val sessionId: String? = null,
    val usage: SessionUsage = SessionUsage(),
    val open: Boolean = false,
    val balance: ProviderBalance? = null,
    val balanceStatus: String = "no-key",
    val balanceLoading: Boolean = false,
    val balanceCheckedAt: Long? = null,
    val threshold: BigDecimal = BigDecimal("5"),
    val dismissed: Boolean = false,
    val error: String? = null,
    val balanceSource: UsageBalanceSource = UsageBalanceSource.None,
) {
    val low
        get() =
            balance?.let { !it.available || threshold.signum() > 0 && it.total < threshold } == true

    val banner
        get() = threshold.signum() > 0 && low && !dismissed
}

sealed interface UsageAction {
    data object Open : UsageAction

    data object Close : UsageAction

    data object Refresh : UsageAction

    data class Visibility(val visible: Boolean) : UsageAction

    data object DismissBalance : UsageAction

    data class Threshold(val value: BigDecimal) : UsageAction

    class CheckKey(val key: ProviderKey) : UsageAction {
        override fun toString() = "CheckBalanceKey"
    }
}
