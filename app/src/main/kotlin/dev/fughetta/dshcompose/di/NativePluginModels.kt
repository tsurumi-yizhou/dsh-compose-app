package dev.fughetta.dshcompose.di

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createNativePlugins() = run {
    ownedModel {
        dev.fughetta.dshcompose.nativeplugins.NativePluginsViewModel(
            app,
            null,
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "native",
            moduleRegistry = featureModules,
        )
    }
}
