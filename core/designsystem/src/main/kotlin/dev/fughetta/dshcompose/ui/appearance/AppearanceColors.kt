package dev.fughetta.dshcompose.ui.appearance

import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.*

val appearancePresets =
    linkedMapOf(
        "mist" to 0xFF4B67B4.toInt(),
        "daiqing" to 0xFF236185.toInt(),
        "dusk" to 0xFF726291.toInt(),
        "turquoise" to 0xFF34766F.toInt(),
        "amber" to 0xFF8D6636.toInt(),
        "graphite" to 0xFF2C2E32.toInt(),
    )

/** OKLab matrices by Björn Ottosson (public domain), shared with the existing palette source. */
data class AppearanceOklch(val lightness: Double, val chroma: Double, val hue: Double) {
    fun color(): Color {
        fun linear(chroma: Double): DoubleArray {
            val radians = hue * PI / 180
            val a = chroma * cos(radians)
            val b = chroma * sin(radians)
            val l = (lightness + .3963377774 * a + .2158037573 * b).pow(3)
            val m = (lightness - .1055613458 * a - .0638541728 * b).pow(3)
            val s = (lightness - .0894841775 * a - 1.2914855480 * b).pow(3)
            return doubleArrayOf(
                4.0767416621 * l - 3.3077115913 * m + .2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s,
                -.0041960863 * l - .7034186147 * m + 1.7076147010 * s,
            )
        }
        var low = 0.0
        var high = chroma.coerceAtLeast(0.0)
        var rgb = linear(high)
        if (rgb.any { it !in -.0001..1.0001 }) {
            repeat(20) {
                val mid = (low + high) / 2
                if (linear(mid).all { value -> value in -.0001..1.0001 }) low = mid else high = mid
            }
            rgb = linear(low)
        }
        fun gamma(value: Double): Int {
            val n = value.coerceIn(0.0, 1.0)
            return ((if (n <= .0031308) 12.92 * n else 1.055 * n.pow(1 / 2.4) - .055) * 255)
                .roundToInt()
                .coerceIn(0, 255)
        }
        return Color(
            0xFF000000.toInt() or (gamma(rgb[0]) shl 16) or (gamma(rgb[1]) shl 8) or gamma(rgb[2]),
        )
    }

    companion object {
        fun from(argb: Int): AppearanceOklch {
            fun linear(shift: Int): Double {
                val value = ((argb ushr shift) and 255) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            val r = linear(16)
            val g = linear(8)
            val b = linear(0)
            val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
            val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
            val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
            val a = 1.9779984951 * l - 2.4285922050 * m + .4505937099 * s
            val bb = .0259040371 * l + .7827717662 * m - .8086757660 * s
            return AppearanceOklch(
                .2104542553 * l + .7936177850 * m - .0040720468 * s,
                sqrt(a * a + bb * bb),
                (atan2(bb, a) * 180 / PI + 360) % 360,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun appearanceColorScheme(preferences: AppearancePreferences, dark: Boolean): ColorScheme {
    val seed =
        if (preferences.source == AccentSource.Custom) preferences.customArgb
        else appearancePresets[preferences.preset] ?: appearancePresets.getValue("mist")
    val origin = AppearanceOklch.from(seed)
    val chroma = origin.chroma.coerceAtMost(.16)
    fun tone(l: Double, c: Double = chroma, h: Double = origin.hue) =
        AppearanceOklch(l, c, h).color()
    val base = if (dark) darkColorScheme() else expressiveLightColorScheme()
    return base.copy(
        primary = tone(if (dark) .79 else .50),
        onPrimary = if (dark) tone(.20, .04) else Color.White,
        primaryContainer = tone(if (dark) .35 else .93, chroma * .38),
        onPrimaryContainer = tone(if (dark) .93 else .29, chroma * .55),
        secondary = tone(if (dark) .80 else .48, chroma * .45),
        onSecondary = if (dark) tone(.20, .02) else Color.White,
        secondaryContainer = tone(if (dark) .34 else .92, chroma * .22),
        onSecondaryContainer = tone(if (dark) .93 else .27, chroma * .32),
        tertiary = tone(if (dark) .81 else .49, chroma * .65, origin.hue + 65),
        onTertiary = if (dark) tone(.20, .02) else Color.White,
        tertiaryContainer = tone(if (dark) .34 else .92, chroma * .28, origin.hue + 65),
        onTertiaryContainer = tone(if (dark) .94 else .27, chroma * .4, origin.hue + 65),
        background = tone(if (dark) .17 else .985, .009),
        onBackground = tone(if (dark) .94 else .21, .012),
        surface = tone(if (dark) .17 else .985, .009),
        onSurface = tone(if (dark) .94 else .21, .012),
        onSurfaceVariant = tone(if (dark) .79 else .41, .018),
        surfaceContainerLowest = tone(if (dark) .13 else 1.0, .006),
        surfaceContainerLow = tone(if (dark) .21 else .97, .012),
        surfaceContainer = tone(if (dark) .245 else .95, .014),
        surfaceContainerHigh = tone(if (dark) .28 else .93, .015),
        surfaceContainerHighest = tone(if (dark) .32 else .91, .016),
        outline = tone(if (dark) .61 else .56, .018),
        outlineVariant = tone(if (dark) .39 else .84, .015),
        surfaceTint = tone(if (dark) .79 else .50),
    )
}
