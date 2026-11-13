package dev.fughetta.dshcompose.notify

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handleNotifications(action: AppAction): Boolean {
    when (action) {
        is AppAction.NotificationSettings -> {
            notificationSettings.action(action.action)
            return true
        }
        AppAction.OpenNotificationBanner -> {
            notificationSettings.openBanner()
            return true
        }
        AppAction.DismissNotificationBanner -> {
            notificationSettings.dismissBanner()
            return true
        }
        else -> return false
    }
}
