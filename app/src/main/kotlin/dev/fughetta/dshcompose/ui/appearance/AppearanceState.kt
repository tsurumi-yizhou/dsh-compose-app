package dev.fughetta.dshcompose.ui.appearance

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily as UiFontFamily
import kotlinx.collections.immutable.*

enum class AccentSource {
    Preset,
    Custom,
    Wallpaper,
}

enum class FeatureTileStyle {
    Duotone,
    Geometric,
    Glassy,
}

enum class AppearanceFontFamily {
    System,
    SansSerif,
    Serif,
    Monospace,
}

fun AppearanceFontFamily.toFontFamily(): UiFontFamily =
    when (this) {
        AppearanceFontFamily.System -> UiFontFamily.Default
        AppearanceFontFamily.SansSerif -> UiFontFamily.SansSerif
        AppearanceFontFamily.Serif -> UiFontFamily.Serif
        AppearanceFontFamily.Monospace -> UiFontFamily.Monospace
    }

enum class LaunchMediaKind {
    Image,
    Animation,
    Video,
}

enum class LaunchMediaFit {
    Fill,
    Center,
}

enum class LaunchMediaBackdrop {
    Auto,
    Edge,
    White,
    Black,
}

data class AppearanceLaunch(
    val kind: LaunchMediaKind,
    val path: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val poster: String? = null,
    val durationMillis: Long? = null,
    val fit: LaunchMediaFit = LaunchMediaFit.Fill,
    val backdrop: LaunchMediaBackdrop = LaunchMediaBackdrop.Auto,
    val edge: Int? = null,
    val enabled: Boolean = true,
)

data class AppearancePreferences(
    val source: AccentSource = AccentSource.Preset,
    val preset: String = "mist",
    val customArgb: Int = 0xFF5F7A55.toInt(),
    val chatScale: Float = 1f,
    val tileStyle: FeatureTileStyle = FeatureTileStyle.Duotone,
    val launch: AppearanceLaunch? = null,
    val fontFamily: AppearanceFontFamily = AppearanceFontFamily.System,
    val uiScale: Float = 1f,
)

data class AppearanceIcon(val id: String, val picture: Bitmap?)

data class AppearanceState(
    val revision: Long = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val preferences: AppearancePreferences = AppearancePreferences(),
    val wallpaperAccent: Int? = null,
    val currentIcon: String = "mist",
    val icons: PersistentList<AppearanceIcon> = persistentListOf(),
    val launchBitmap: Bitmap? = null,
    val previewLaunch: Boolean = false,
    val shortcutPicture: Bitmap? = null,
    val shortcutLabel: String = "DSH Compose",
    val shortcutZoom: Float = 1f,
    val shortcutX: Float = 0f,
    val shortcutY: Float = 0f,
    val shortcutEditing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

sealed interface AppearanceAction {
    data object Refresh : AppearanceAction

    data class Preset(val id: String) : AppearanceAction

    data class CustomColor(val argb: Int) : AppearanceAction

    data class Source(val source: AccentSource) : AppearanceAction

    data object ImportLaunch : AppearanceAction

    data class LaunchEnabled(val value: Boolean) : AppearanceAction

    data class LaunchFit(val value: LaunchMediaFit) : AppearanceAction

    data class LaunchBackdrop(val value: LaunchMediaBackdrop) : AppearanceAction

    data object PreviewLaunch : AppearanceAction

    data object CloseLaunchPreview : AppearanceAction

    data class Icon(val id: String) : AppearanceAction

    data object ImportShortcut : AppearanceAction

    data class ShortcutLabel(val value: String) : AppearanceAction

    data class ShortcutFrame(val zoom: Float, val x: Float, val y: Float) : AppearanceAction

    data object PinShortcut : AppearanceAction

    data object CloseShortcut : AppearanceAction
}

enum class AppearanceImport {
    Launch,
    Shortcut,
}

sealed interface AppearanceEffect {
    data class PickMedia(val kind: AppearanceImport) : AppearanceEffect

    data object SynchronizeLaunchTheme : AppearanceEffect
}

val LocalAppearance = staticCompositionLocalOf { AppearancePreferences() }
