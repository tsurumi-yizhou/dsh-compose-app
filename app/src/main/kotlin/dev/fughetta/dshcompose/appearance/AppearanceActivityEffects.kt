package dev.fughetta.dshcompose.appearance

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fughetta.dshcompose.launch.LaunchThemeSync
import dev.fughetta.dshcompose.ui.appearance.*
import kotlinx.coroutines.flow.collect

@Composable
fun AppearanceActivityEffects(controller: AppearanceController) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val launch =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            controller.importMedia(AppearanceImport.Launch, it)
        }
    val shortcut =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
            controller.importMedia(AppearanceImport.Shortcut, it)
        }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                is AppearanceEffect.PickMedia ->
                    when (effect.kind) {
                        AppearanceImport.Launch ->
                            launch.launch(arrayOf("image/*", "video/mp4", "video/webm"))
                        AppearanceImport.Shortcut -> shortcut.launch("image/*")
                    }
                AppearanceEffect.SynchronizeLaunchTheme ->
                    context.activity()?.let(LaunchThemeSync::sync)
            }
        }
    }
}

private fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
