package dev.fughetta.dshcompose.di

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createDeepSeekAccount() = run {
    ownedModel { dev.fughetta.dshcompose.account.DeepSeekAccountViewModel() }
}

internal fun AppViewModel.createUsage() = run {
    ownedModel {
        dev.fughetta.dshcompose.transcript.UsageViewModel(app) { scope ->
            dev.fughetta.dshcompose.data.usage.EngineUsageRepository(scope) {
                if (selectedHost == "local")
                    deepSeekAccount.action(
                        dev.fughetta.dshcompose.ui.account.DeepSeekAccountAction.Refresh,
                    )
            }
        }
    }
}
