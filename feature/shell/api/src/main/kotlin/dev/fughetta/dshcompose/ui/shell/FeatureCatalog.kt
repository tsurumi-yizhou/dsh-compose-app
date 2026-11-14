package dev.fughetta.dshcompose.ui.shell

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import java.util.Locale

/** Feature groups used by settings search and the quick panel. */
enum class FeatureSection(@StringRes val title: Int) {
    Chat(CoreDesignsystemR.string.features_section_chat),
    Automation(CoreDesignsystemR.string.features_section_automation),
    Plugins(CoreDesignsystemR.string.features_section_plugins),
    Settings(CoreDesignsystemR.string.features_section_settings),
}

/**
 * One page settings search and the quick panel can open. [id] is a feature id (the same ids
 * `dsh://open/<id>` links use); [keywords] is a comma-separated string resource.
 */
data class FeatureEntry(
    val id: String,
    @StringRes val title: Int,
    @StringRes val keywords: Int,
    val section: FeatureSection,
)

val FeatureCatalog: List<FeatureEntry> =
    listOf(
        FeatureEntry(
            "search",
            CoreDesignsystemR.string.session_search,
            CoreDesignsystemR.string.feature_kw_search,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "workspaces",
            CoreDesignsystemR.string.workspaces,
            CoreDesignsystemR.string.feature_kw_workspaces,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "permissions",
            CoreDesignsystemR.string.permissions_title,
            CoreDesignsystemR.string.feature_kw_permissions,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "agent-presets",
            CoreDesignsystemR.string.agent_presets,
            CoreDesignsystemR.string.feature_kw_agent_presets,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "personalization",
            CoreDesignsystemR.string.reply_prefs_title,
            CoreDesignsystemR.string.feature_kw_personalization,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "reply-prefs",
            CoreDesignsystemR.string.reply_prefs_title,
            CoreDesignsystemR.string.feature_kw_reply_prefs,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "schedules",
            CoreDesignsystemR.string.navigation_schedules,
            CoreDesignsystemR.string.feature_kw_schedules,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone",
            CoreDesignsystemR.string.navigation_phone_controls,
            CoreDesignsystemR.string.feature_kw_phone,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone-dnd",
            CoreDesignsystemR.string.phone_dnd_title,
            CoreDesignsystemR.string.feature_kw_phone_dnd,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone-unlocks",
            CoreDesignsystemR.string.phone_controls_unlocks,
            CoreDesignsystemR.string.feature_kw_phone_unlocks,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone-background-guide",
            CoreDesignsystemR.string.background_guide_title,
            CoreDesignsystemR.string.feature_kw_phone_background_guide,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "terminal",
            CoreDesignsystemR.string.navigation_terminal,
            CoreDesignsystemR.string.feature_kw_terminal,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "plugins",
            CoreDesignsystemR.string.navigation_plugins,
            CoreDesignsystemR.string.feature_kw_plugins,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "plugin-inventory",
            CoreDesignsystemR.string.navigation_plugin_panels,
            CoreDesignsystemR.string.feature_kw_plugin_inventory,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "cordis",
            CoreDesignsystemR.string.cordis_title,
            CoreDesignsystemR.string.feature_kw_cordis,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "native-plugins",
            CoreDesignsystemR.string.navigation_native_plugins,
            CoreDesignsystemR.string.feature_kw_native_plugins,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "media",
            CoreDesignsystemR.string.navigation_media_settings,
            CoreDesignsystemR.string.feature_kw_media,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "remote",
            CoreDesignsystemR.string.navigation_compose_services,
            CoreDesignsystemR.string.feature_kw_remote,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "settings",
            CoreDesignsystemR.string.settings,
            CoreDesignsystemR.string.feature_kw_settings,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "providers",
            CoreDesignsystemR.string.providers,
            CoreDesignsystemR.string.feature_kw_providers,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "account",
            CoreDesignsystemR.string.navigation_deepseek_account,
            CoreDesignsystemR.string.feature_kw_account,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "appearance",
            CoreDesignsystemR.string.appearance,
            CoreDesignsystemR.string.feature_kw_appearance,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "notifications",
            CoreDesignsystemR.string.navigation_notifications,
            CoreDesignsystemR.string.feature_kw_notifications,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "engine",
            CoreDesignsystemR.string.engine_title,
            CoreDesignsystemR.string.feature_kw_engine,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "engine-settings",
            CoreDesignsystemR.string.navigation_engine_configuration,
            CoreDesignsystemR.string.feature_kw_engine_settings,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "quick-panel",
            CoreDesignsystemR.string.quick_panel_title,
            CoreDesignsystemR.string.feature_kw_quick_panel,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "about",
            CoreDesignsystemR.string.about_title,
            CoreDesignsystemR.string.feature_kw_about,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "licenses",
            CoreDesignsystemR.string.open_source_licenses,
            CoreDesignsystemR.string.feature_kw_licenses,
            FeatureSection.Settings,
        ),
    )

val LocalFeatureCatalog = staticCompositionLocalOf { FeatureCatalog }

fun featureEntry(id: String): FeatureEntry? = FeatureCatalog.firstOrNull { it.id == id }

/** Lower case, full-width forms folded to ASCII, no spaces: 「ＡＰＩ Key」 and 「apikey」 read alike. */
fun normalizeQuery(text: String): String = buildString {
    for (ch in text) {
        if (ch.isWhitespace() || ch == '　') continue
        append(if (ch in '！'..'～') ch - 0xFEE0 else ch)
    }
}
    .lowercase()

/** An entry's words in one language. */
data class FeatureText(val title: String, val keywords: List<String>, val section: String)

/** How well [query] (normalized) meets [text]; 0 is no match. */
internal fun score(query: String, text: FeatureText): Int {
    val title = normalizeQuery(text.title)
    val keywords = text.keywords.map(::normalizeQuery)
    return when {
        title == query -> 100
        title.startsWith(query) -> 80
        query in title -> 60
        keywords.any { it == query } -> 50
        keywords.any { query in it } -> 40
        query in normalizeQuery(text.section) -> 20
        else -> 0
    }
}

/** Entries matching [query] in any of their languages, best first, catalog order on ties. */
fun rankFeatures(query: String, texts: Map<FeatureEntry, List<FeatureText>>): List<FeatureEntry> {
    val q = normalizeQuery(query)
    if (q.isEmpty()) return emptyList()
    return texts.entries
        .map { (entry, words) -> entry to words.maxOf { score(q, it) } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
}

/**
 * Every entry's words in the app's language and in the other one (Chinese and English), so a query
 * finds a page in either whatever the app shows.
 */
@Composable
fun rememberFeatureTexts(): Map<FeatureEntry, List<FeatureText>> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val catalog = LocalFeatureCatalog.current
    return remember(context, configuration, catalog) {
        val current = configuration.locales[0]
        val other = if (current.language == "zh") Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        val contexts = listOf(context, context.localized(configuration, other))
        catalog.associateWith { entry ->
            contexts.map { c ->
                FeatureText(
                    c.getString(entry.title),
                    c.getString(entry.keywords)
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty),
                    c.getString(entry.section.title),
                )
            }
        }
    }
}

private fun Context.localized(base: Configuration, locale: Locale): Context =
    createConfigurationContext(Configuration(base).apply { setLocale(locale) })
