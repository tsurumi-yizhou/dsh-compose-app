package dev.fughetta.dshcompose.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import dev.fughetta.dshcompose.ui.appearance.*

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    mode: ThemeMode,
    dynamicColor: Boolean,
    appearance: AppearancePreferences? = null,
    content: @Composable () -> Unit,
) {
    val dark =
        when (mode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val context = LocalContext.current
    val palette =
        when {
            (appearance?.source == AccentSource.Wallpaper || appearance == null && dynamicColor) &&
                Build.VERSION.SDK_INT >= 31 ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            appearance != null -> appearanceColorScheme(appearance, dark)
            dark -> darkColorScheme()
            else -> expressiveLightColorScheme()
        }
    val colors =
        if (dark)
            palette.copy(onSurfaceVariant = lerp(palette.onSurfaceVariant, palette.onSurface, .25f))
        else palette
    val family = (appearance?.fontFamily ?: AppearanceFontFamily.System).toFontFamily()
    val scale = appearance?.uiScale ?: 1f
    val typography = remember(family) { typographyWith(family) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalAppearance provides (appearance ?: AppearancePreferences()),
        LocalAppearanceCodeFont provides appearanceCodeFont(),
        LocalExtendedColors provides extendedColors(colors.primary, dark),
        LocalContentColor provides colors.onSurface,
        LocalDensity provides Density(density.density, density.fontScale * scale),
    ) {
        MaterialExpressiveTheme(colorScheme = colors, typography = typography, content = content)
    }
}

/** Apply [family] to every Material text style, including the expressive emphasized ones. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun typographyWith(family: FontFamily): Typography =
    Typography().run {
        fun TextStyle.withFamily() = copy(fontFamily = family)
        copy(
            displayLarge = displayLarge.withFamily(),
            displayMedium = displayMedium.withFamily(),
            displaySmall = displaySmall.withFamily(),
            headlineLarge = headlineLarge.withFamily(),
            headlineMedium = headlineMedium.withFamily(),
            headlineSmall = headlineSmall.withFamily(),
            titleLarge = titleLarge.withFamily(),
            titleMedium = titleMedium.withFamily(),
            titleSmall = titleSmall.withFamily(),
            bodyLarge = bodyLarge.withFamily(),
            bodyMedium = bodyMedium.withFamily(),
            bodySmall = bodySmall.withFamily(),
            labelLarge = labelLarge.withFamily(),
            labelMedium = labelMedium.withFamily(),
            labelSmall = labelSmall.withFamily(),
            displayLargeEmphasized = displayLargeEmphasized.withFamily(),
            displayMediumEmphasized = displayMediumEmphasized.withFamily(),
            displaySmallEmphasized = displaySmallEmphasized.withFamily(),
            headlineLargeEmphasized = headlineLargeEmphasized.withFamily(),
            headlineMediumEmphasized = headlineMediumEmphasized.withFamily(),
            headlineSmallEmphasized = headlineSmallEmphasized.withFamily(),
            titleLargeEmphasized = titleLargeEmphasized.withFamily(),
            titleMediumEmphasized = titleMediumEmphasized.withFamily(),
            titleSmallEmphasized = titleSmallEmphasized.withFamily(),
            bodyLargeEmphasized = bodyLargeEmphasized.withFamily(),
            bodyMediumEmphasized = bodyMediumEmphasized.withFamily(),
            bodySmallEmphasized = bodySmallEmphasized.withFamily(),
            labelLargeEmphasized = labelLargeEmphasized.withFamily(),
            labelMediumEmphasized = labelMediumEmphasized.withFamily(),
            labelSmallEmphasized = labelSmallEmphasized.withFamily(),
        )
    }
