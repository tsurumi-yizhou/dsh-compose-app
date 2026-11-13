package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * @guide 09-appearance @guideZh
 *
 * # 外观
 *
 * 调整明暗模式和主题配色。
 *
 * `{{path:settings,appearance}}` · [打开](dsh://open/appearance)
 *
 * - **主题**：**跟随系统**、**浅色**或**深色**。
 * - **主题色**：雾蓝、黛青、暮紫、松绿、琥珀、石墨等预设，或填写**十六进制颜色**用**自定义颜色**；也可以用**系统壁纸取色**。
 *
 * @guideEn
 *
 * # Appearance
 *
 * Adjust light and dark mode and accent colors.
 *
 * `{{path:settings,appearance}}` · [Open](dsh://open/appearance)
 *
 * - **Theme**: **System**, **Light** or **Dark**.
 * - **Accent color**: presets such as Mist, Daiqing, Dusk, Turquoise, Amber and Graphite, or a
 *   **Hex color** as a **Custom color**; or use **System wallpaper color**.
 */
@Serializable internal data object Appearance : NavKey
