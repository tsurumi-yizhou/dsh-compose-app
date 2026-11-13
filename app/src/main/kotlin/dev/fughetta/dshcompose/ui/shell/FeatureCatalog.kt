package dev.fughetta.dshcompose.ui.shell

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.fughetta.dshcompose.R
import java.util.Locale

/** Feature groups used by settings search and the quick panel. */
enum class FeatureSection(@StringRes val title: Int) {
    Chat(R.string.features_section_chat),
    Automation(R.string.features_section_automation),
    Plugins(R.string.features_section_plugins),
    Settings(R.string.features_section_settings),
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
            R.string.session_search,
            R.string.feature_kw_search,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "workspaces",
            R.string.workspaces,
            R.string.feature_kw_workspaces,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "permissions",
            R.string.permissions_title,
            R.string.feature_kw_permissions,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "agent-presets",
            R.string.agent_presets,
            R.string.feature_kw_agent_presets,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "personalization",
            R.string.reply_prefs_title,
            R.string.feature_kw_personalization,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "reply-prefs",
            R.string.reply_prefs_title,
            R.string.feature_kw_reply_prefs,
            FeatureSection.Chat,
        ),
        FeatureEntry(
            "schedules",
            R.string.navigation_schedules,
            R.string.feature_kw_schedules,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone",
            R.string.navigation_phone_controls,
            R.string.feature_kw_phone,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone-dnd",
            R.string.phone_dnd_title,
            R.string.feature_kw_phone_dnd,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone-unlocks",
            R.string.phone_controls_unlocks,
            R.string.feature_kw_phone_unlocks,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "phone-background-guide",
            R.string.background_guide_title,
            R.string.feature_kw_phone_background_guide,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "terminal",
            R.string.navigation_terminal,
            R.string.feature_kw_terminal,
            FeatureSection.Automation,
        ),
        FeatureEntry(
            "plugins",
            R.string.navigation_plugins,
            R.string.feature_kw_plugins,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "plugin-inventory",
            R.string.navigation_plugin_panels,
            R.string.feature_kw_plugin_inventory,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "cordis",
            R.string.cordis_title,
            R.string.feature_kw_cordis,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "native-plugins",
            R.string.navigation_native_plugins,
            R.string.feature_kw_native_plugins,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "media",
            R.string.navigation_media_settings,
            R.string.feature_kw_media,
            FeatureSection.Plugins,
        ),
        FeatureEntry(
            "remote",
            R.string.navigation_compose_services,
            R.string.feature_kw_remote,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "settings",
            R.string.settings,
            R.string.feature_kw_settings,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "providers",
            R.string.providers,
            R.string.feature_kw_providers,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "account",
            R.string.navigation_deepseek_account,
            R.string.feature_kw_account,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "appearance",
            R.string.appearance,
            R.string.feature_kw_appearance,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "notifications",
            R.string.navigation_notifications,
            R.string.feature_kw_notifications,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "engine",
            R.string.engine_title,
            R.string.feature_kw_engine,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "engine-settings",
            R.string.navigation_engine_configuration,
            R.string.feature_kw_engine_settings,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "quick-panel",
            R.string.quick_panel_title,
            R.string.feature_kw_quick_panel,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "about",
            R.string.about_title,
            R.string.feature_kw_about,
            FeatureSection.Settings,
        ),
        FeatureEntry(
            "licenses",
            R.string.open_source_licenses,
            R.string.feature_kw_licenses,
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
