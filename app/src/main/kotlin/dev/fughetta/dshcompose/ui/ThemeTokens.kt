package dev.fughetta.dshcompose.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.ui.appearance.AppearanceOklch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** Spacing scale on the 4dp grid; use for padding and gaps, not for component sizes. */
internal object Spacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
}

/**
 * Status roles Material 3 leaves to apps, paired the same way as
 * primary/onPrimary/primaryContainer/onPrimaryContainer.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

/**
 * Fixed green/amber hues, harmonized toward [primary] (half the hue gap, at most 15°, as in
 * Material color harmonization) so they sit in the same palette.
 */
fun extendedColors(primary: Color, dark: Boolean): ExtendedColors {
    val source = AppearanceOklch.from(primary.toArgb()).hue
    fun harmonize(hue: Double): Double {
        val delta = ((source - hue + 540) % 360) - 180
        return (hue + sign(delta) * min(abs(delta) / 2, 15.0) + 360) % 360
    }
    fun roles(hue: Double): List<Color> {
        val h = harmonize(hue)
        fun tone(l: Double, c: Double) = AppearanceOklch(l, c, h).color()
        return listOf(
            tone(if (dark) .80 else .52, .13),
            tone(if (dark) .22 else .99, if (dark) .05 else 0.0),
            tone(if (dark) .36 else .93, .07),
            tone(if (dark) .93 else .30, .08),
        )
    }
    val (s, onS, sc, onSc) = roles(150.0)
    val (w, onW, wc, onWc) = roles(75.0)
    return ExtendedColors(s, onS, sc, onSc, w, onW, wc, onWc)
}

val LocalExtendedColors = staticCompositionLocalOf {
    extendedColors(Color(0xFF4B67B4), dark = false)
}

val MaterialTheme.extendedColors: ExtendedColors
    @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
