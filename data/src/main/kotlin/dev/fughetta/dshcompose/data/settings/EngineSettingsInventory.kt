package dev.fughetta.dshcompose.data.settings

import dev.fughetta.dshcompose.domain.plugins.*
import dev.fughetta.dshcompose.domain.settings.EngineSettingsInventory
import dev.fughetta.dshcompose.domain.settings.EngineSettingsPorts
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

suspend fun readEngineSettings(owner: EngineSettingsPorts): EngineSettingsInventory =
    withTimeout(20000) {
        coroutineScope {
            val description = async { owner.settings.describe() }
            val bundles = async { owner.plugins.bundles() }
            val complete = async {
                runCatching {
                    if (owner.settings.canReadCompletePluginConfig) owner.plugins.config()
                    else persistentListOf()
                }
                    .onFailure { if (it is CancellationException) throw it }
            }
            EngineSettingsInventory(description.await(), bundles.await(), complete.await())
        }
    }
