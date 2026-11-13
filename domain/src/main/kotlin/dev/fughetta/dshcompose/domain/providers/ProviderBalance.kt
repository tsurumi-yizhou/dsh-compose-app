package dev.fughetta.dshcompose.domain.providers

import dev.fughetta.dshcompose.domain.account.AccountWallet
import dev.fughetta.dshcompose.domain.account.DeepSeekAccountBalance
import java.math.BigDecimal

const val deepSeekTopUpUrl = "https://platform.deepseek.com/top_up"

data class ProviderBalance(
    val currency: String,
    val total: BigDecimal,
    val granted: BigDecimal? = null,
    val paid: BigDecimal? = null,
    val available: Boolean = true,
) {
    val symbol
        get() = if (currency == "USD") "$" else "¥"

    companion object {
        fun account(value: DeepSeekAccountBalance): ProviderBalance? {
            val all = value.recharge + value.bonus
            if (all.isEmpty()) return null
            val currency =
                all.firstOrNull { it.currency == "CNY" }?.currency ?: all.first().currency
            fun sum(rows: List<AccountWallet>) =
                rows
                    .filter { it.currency == currency }
                    .fold(BigDecimal.ZERO) { total, row ->
                        total + (row.balance.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                    }
            val paid = sum(value.recharge)
            val bonus = sum(value.bonus)
            return ProviderBalance(currency, paid + bonus, bonus, paid)
        }
    }
}

sealed interface ProviderBalanceResult {
    data class Ready(val balance: ProviderBalance) : ProviderBalanceResult

    data object InvalidKey : ProviderBalanceResult

    data object Unavailable : ProviderBalanceResult
}
