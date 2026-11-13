package dev.fughetta.dshcompose.transcript

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.providers.deepSeekTopUpUrl
import dev.fughetta.dshcompose.domain.usage.UsageBalanceSource
import dev.fughetta.dshcompose.sessions.openPublicLink
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handleUsageAccount(action: AppAction): Boolean {
    when (action) {
        is AppAction.Usage -> {
            usage.action(action.action)
            return true
        }
        AppAction.TopUpBalance -> {
            when (state.value.usage.balanceSource) {
                dev.fughetta.dshcompose.domain.usage.UsageBalanceSource.Account ->
                    deepSeekAccount.action(
                        dev.fughetta.dshcompose.ui.account.DeepSeekAccountAction.TopUp,
                    )
                dev.fughetta.dshcompose.domain.usage.UsageBalanceSource.ApiKey ->
                    openPublicLink(deepSeekTopUpUrl)
                dev.fughetta.dshcompose.domain.usage.UsageBalanceSource.None ->
                    onAction(AppAction.OpenDeepSeekAccount)
            }
            return true
        }
        AppAction.OpenDeepSeekAccount -> {
            mutableState.update {
                it.copy(
                    featurePage = "account",
                    featurePageRevision = it.featurePageRevision + 1,
                )
            }
            return true
        }
        is AppAction.DeepSeekAccount -> {
            deepSeekAccount.action(action.action)
            return true
        }
        else -> return false
    }
}
