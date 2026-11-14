package dev.fughetta.dshcompose.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*

@Composable
fun AppearanceBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(MaterialTheme.colorScheme.background)) { content() }
}

/** Scope chat typography without changing the size of navigation or controls. */
@Composable
fun AppearanceChatScale(content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scale = LocalAppearance.current.chatScale
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides
            Density(density.density, density.fontScale * scale),
    ) {
        content()
    }
}
