package dev.fughetta.dshcompose.ui.phone

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Applied to expanding replies and generated content; no per-frame AppState mutation. */
fun Modifier.performanceContentSize(settings: PerformanceSettingsState): Modifier =
    animateContentSize(
        animationSpec =
            when {
                !settings.animations -> snap()
                settings.fluid -> spring(dampingRatio = .85f, stiffness = Spring.StiffnessMediumLow)
                else -> tween(140)
            },
    )

@Composable
fun PerformanceAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    settings: PerformanceSettingsState = LocalPerformanceSettings.current,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val enter =
        if (!settings.animations) EnterTransition.None
        else
            fadeIn(if (settings.fluid) spring() else tween(140)) +
                expandVertically(if (settings.fluid) spring() else tween(140))
    val exit =
        if (!settings.animations) ExitTransition.None
        else fadeOut(tween(100)) + shrinkVertically(tween(100))
    AnimatedVisibility(visible, modifier, enter, exit, content = content)
}
