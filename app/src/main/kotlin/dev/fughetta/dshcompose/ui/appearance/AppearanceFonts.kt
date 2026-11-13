package dev.fughetta.dshcompose.ui.appearance

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*

val LocalAppearanceCodeFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/** Reuses the existing bundled font files; no duplicate resource copy or font download. */
@Composable
fun appearanceCodeFont(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) {
        FontFamily(
            Font("fonts/JetBrainsMono-Regular.ttf", assets),
            Font("fonts/JetBrainsMono-Medium.ttf", assets, weight = FontWeight.Medium),
            Font("fonts/JetBrainsMono-Bold.ttf", assets, weight = FontWeight.Bold),
            Font("fonts/JetBrainsMono-Italic.ttf", assets, style = FontStyle.Italic),
        )
    }
}
