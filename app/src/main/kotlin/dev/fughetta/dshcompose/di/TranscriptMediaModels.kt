package dev.fughetta.dshcompose.di

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.data.media.EngineMediaRepository
import dev.fughetta.dshcompose.domain.media.MediaRepository
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createReplyImageDownloads(): MediaRepository = EngineMediaRepository(app)

internal fun AppViewModel.createReplyImageLoader() = run {
    dev.fughetta.dshcompose.images.replyImageLoader(
        app,
        replyImageDownloads,
        { connectionNumber },
        { connectionSession?.repositories?.files },
    )
}

internal fun AppViewModel.createNativeImageExports() = run {
    dev.fughetta.dshcompose.images.NativeImageExports(
        app,
        replyImageDownloads,
        replyImageLoader,
        { connectionNumber },
        { connectionSession?.repositories?.files },
    )
}

internal fun AppViewModel.createImageExports() = run {
    dev.fughetta.dshcompose.images.ImageExportController(
        viewModelScope,
        stage = nativeImageExports::stage,
        save = nativeImageExports::save,
        share = { nativeImageExports.share(it) },
        hasPermission = {
            app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
        legacyPermission = { android.os.Build.VERSION.SDK_INT < 29 },
        publish = { value -> mutableState.update { it.copy(imageExport = value) } },
    )
}

internal fun AppViewModel.createTranscriptArtifacts() = run {
    ownedModel {
        dev.fughetta.dshcompose.transcript.TranscriptArtifactViewModel(
            null,
            app,
        )
    }
}

internal fun AppViewModel.createMediaGeneration() = run {
    ownedModel {
        dev.fughetta.dshcompose.media.MediaGenerationViewModel(
            app,
            (app as ClientApplication).mediaRepository,
        )
    }
}
