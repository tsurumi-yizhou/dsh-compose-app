package dev.fughetta.dshcompose.domain.account

import java.math.BigDecimal
import java.math.RoundingMode

enum class AccountSignInPhase(val wire: String) {
    Initializing("initializing"),
    WaitingBrowser("waiting-browser"),
    Exchanging("exchanging"),
    Committing("committing"),
    Succeeded("succeeded"),
    Cancelled("cancelled"),
    Expired("expired"),
    Failed("failed");

    val active
        get() = this in setOf(Initializing, WaitingBrowser, Exchanging, Committing)
}

class AccountSignInAttempt(
    val id: String,
    val phase: AccountSignInPhase,
    val authorizeUrl: String?,
    val expiresAt: Long?,
    val error: String?,
) {
    override fun toString() = "AccountSignInAttempt(phase=$phase)"
}

data class DeepSeekAccountView(
    val signedIn: Boolean,
    val usageUrl: String?,
    val topUpUrl: String?,
    val attempt: AccountSignInAttempt?,
)

data class DeepSeekAccountProfile(
    val id: String?,
    val name: String?,
    val contact: String?,
    val avatarUrl: String?,
)

data class AccountWallet(val currency: String, val balance: String)

data class DeepSeekAccountBalance(val recharge: List<AccountWallet>, val bonus: List<AccountWallet>)

/** Preserve decimal precision, including scientific zero and sub-cent Platform balances. */
fun formatAccountWallet(raw: String): String {
    val amount =
        try {
            BigDecimal(raw.trim())
        } catch (_: NumberFormatException) {
            return raw
        }
    if (amount.signum() == 0) return "0.00"
    val rounded =
        if (amount.signum() > 0) amount.setScale(2, RoundingMode.DOWN)
        else amount.setScale(2, RoundingMode.HALF_UP)
    if (rounded.signum() == 0) return if (amount.signum() > 0) "<0.01" else "-0.01"
    val plain = rounded.abs().toPlainString()
    val parts = plain.split('.')
    val grouped = parts[0].reversed().chunked(3).joinToString(",").reversed()
    return (if (amount.signum() < 0) "-" else "") +
        grouped +
        "." +
        parts.getOrElse(1) { "00" }.padEnd(2, '0')
}

fun sumAccountWallets(wallets: List<AccountWallet>, currency: String): String? {
    val amounts =
        wallets
            .filter { it.currency == currency }
            .mapNotNull { runCatching { BigDecimal(it.balance) }.getOrNull() }
    return amounts
        .takeIf { it.isNotEmpty() }
        ?.fold(BigDecimal.ZERO, BigDecimal::add)
        ?.toPlainString()
}
