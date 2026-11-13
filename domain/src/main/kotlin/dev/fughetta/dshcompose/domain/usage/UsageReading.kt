package dev.fughetta.dshcompose.domain.usage

import dev.fughetta.dshcompose.domain.providers.ProviderBalance

/** Readback of the selected host/session, independent of panel visibility and presentation. */
data class UsageReading(
    val hostKey: String? = null,
    val sessionId: String? = null,
    val connection: Long = 0,
    val usage: SessionUsage = SessionUsage(),
    val balance: ProviderBalance? = null,
    val balanceStatus: String = "no-key",
    val balanceLoading: Boolean = false,
    val balanceCheckedAt: Long? = null,
    val balanceSource: UsageBalanceSource = UsageBalanceSource.None,
    val balanceRevision: Long = 0,
    val error: String? = null,
)
