package dev.fughetta.dshcompose.di

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.notificationResponse
import dev.fughetta.dshcompose.notificationTap
import dev.fughetta.dshcompose.notify.Notifications
import dev.fughetta.dshcompose.notify.openNotification
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createNotifications() = run {
    Notifications(app, onTap = ::notificationTap, onAction = ::notificationResponse)
}

internal fun AppViewModel.createNotificationSettings() = run {
    ownedModel {
        dev.fughetta.dshcompose.notify.NotificationsViewModel(
            app,
            null,
            notifications,
            notificationPreferences,
            createSessions = { scope, callbacks ->
                dev.fughetta.dshcompose.data.notify.EngineNotificationsRepository(
                    scope,
                    callbacks,
                )
            },
            onOpen = ::openNotification,
            onError = { code -> mutableState.update { it.copy(error = code) } },
            awaitPhoneReturn = { repository, generation, id ->
                val lease = localObserver.lease
                if (
                    lease != null &&
                        lease.generation == generation &&
                        lease.notifications === repository
                )
                    phoneTurnResults.await(lease, generation, id)
                else null
            },
        )
    }
}
