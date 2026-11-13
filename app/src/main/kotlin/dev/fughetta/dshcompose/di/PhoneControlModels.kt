package dev.fughetta.dshcompose.di

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.notify.revealLocalSession
import dev.fughetta.dshcompose.phoneFailure
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createPerformanceMonitor() = run {
    dev.fughetta.dshcompose.phoneui.PhonePerformanceMonitor(app, viewModelScope)
}

internal fun AppViewModel.createPhoneTurnEnd() = run {
    dev.fughetta.dshcompose.phoneui.PhoneTurnEndCoordinator(
        app,
        viewModelScope,
        localOwner = { localObserver.lease },
        runningLocalSessions = { hosts.runningLocalSessions(localObserver) },
        returnAfterDone = { phoneControls.returnAfterDone },
        revealSession = ::revealLocalSession,
        cancelLocalTurns = { hosts.cancelLocalSessions(localObserver) },
        onError = ::phoneFailure,
    )
}

internal fun AppViewModel.createPhoneTurnResults() = run {
    dev.fughetta.dshcompose.phoneui.PhoneTurnResults(viewModelScope)
}

internal fun AppViewModel.createBackgroundNet() = run {
    dev.fughetta.dshcompose.runtime.BackgroundNetController(
        app,
        viewModelScope,
        dev.fughetta.dshcompose.runtime.VendorSettings.vendorOf(
            android.os.Build.MANUFACTURER,
            android.os.Build.BRAND,
        ),
        exempt = {
            app.getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(app.packageName)
        },
        blockedAt = { runtime.status()["networkBlockedAt"] as? Long },
        retries = {
            val chat = visibleSessionId?.takeIf { selectedHost == "local" }
            conversations.state.value.chats[chat]
                ?.messages
                ?.filter { it.noticeKind == "retry" && it.notice?.code == "TRANSPORT" }
                ?.mapTo(mutableSetOf()) { it.key }
                .orEmpty()
        },
    )
}
