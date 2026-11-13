package dev.fughetta.dshcompose.notify

import android.app.Application
import dev.fughetta.dshcompose.ui.notify.*
import dev.fughetta.dshcompose.ui.notify.NotificationSettingsAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationSettingsViewModelTest {
    private class Hooks {
        var openedSettings: String? = null
        var openedSettingsCalls = 0
        var openedLive = 0
        var openedNowBar = 0
        var enabledNowBar = false
        var refreshes = 0
        var disabled = 0
        var channelsChanged = 0
        val errors = mutableListOf<String>()
    }

    private fun controller(scope: CoroutineScope, hooks: Hooks) =
        NotificationSettingsViewModel(
            context = RuntimeEnvironment.getApplication(),
            parent = scope,
        ) { effect ->
            when (effect) {
                is NotificationSettingsEffect.OpenSystem -> {
                    hooks.openedSettings = effect.channel
                    hooks.openedSettingsCalls++
                }
                NotificationSettingsEffect.OpenLiveSystem -> hooks.openedLive++
                NotificationSettingsEffect.OpenNowBarSystem -> hooks.openedNowBar++
                NotificationSettingsEffect.EnableNowBar -> hooks.enabledNowBar = true
                NotificationSettingsEffect.Refresh -> hooks.refreshes++
                NotificationSettingsEffect.Disabled -> hooks.disabled++
                NotificationSettingsEffect.ChannelsChanged -> hooks.channelsChanged++
                is NotificationSettingsEffect.Error -> hooks.errors += effect.code
            }
        }

    private suspend fun settle(controller: NotificationSettingsViewModel) {
        withTimeout(5_000) { while (controller.state.value.busy) delay(10) }
    }

    @Test
    fun preferencesStartEnabledAndASavedToggleIsPersisted() = runBlocking {
        val hooks = Hooks()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = controller(scope, hooks)
            assertTrue(controller.state.value.live)

            controller.action(
                NotificationSettingsAction.Preference(NotificationPreference.Live, false),
            )
            settle(controller)

            assertFalse(controller.state.value.live)
            assertTrue(hooks.channelsChanged > 0)
            val again = controller(CoroutineScope(SupervisorJob() + Dispatchers.Default), Hooks())
            assertFalse(again.state.value.live)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disablingNotificationsRunsTheDisabledHookAndNonChannelTogglesDoNot() = runBlocking {
        val hooks = Hooks()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = controller(scope, hooks)

            controller.action(
                NotificationSettingsAction.Preference(NotificationPreference.Approval, false),
            )
            settle(controller)
            assertEquals(0, hooks.channelsChanged)

            controller.action(
                NotificationSettingsAction.Preference(NotificationPreference.Enabled, false),
            )
            settle(controller)
            assertEquals(1, hooks.disabled)
            assertTrue(hooks.channelsChanged > 0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun systemActionsForwardToTheirHooks() = runBlocking {
        val hooks = Hooks()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = controller(scope, hooks)

            controller.action(NotificationSettingsAction.OpenSystem("done"))
            assertEquals("done", hooks.openedSettings)
            controller.action(NotificationSettingsAction.OpenLiveSystem)
            controller.action(NotificationSettingsAction.OpenNowBarSystem)
            assertEquals(1, hooks.openedLive)
            assertEquals(1, hooks.openedNowBar)
            controller.action(NotificationSettingsAction.Refresh)
            assertEquals(1, hooks.refreshes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aPermissionRequestIsHandedOutOnceAndThenClaimed() = runBlocking {
        val hooks = Hooks()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = controller(scope, hooks)
            assertNull(controller.state.value.permissionRequest)

            controller.action(NotificationSettingsAction.RequestPermission)
            // SDK 28 has no runtime notification permission, so the settings page opens instead.
            assertEquals(1, hooks.openedSettingsCalls)
            assertNull(controller.state.value.permissionRequest)

            assertFalse(controller.claimPermission("missing"))
            controller.mutable.update { it.copy(permissionRequest = "request-id") }
            assertTrue(controller.claimPermission("request-id"))
            assertFalse(controller.claimPermission("request-id"))
        } finally {
            scope.cancel()
        }
    }
}
