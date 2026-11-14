package dev.fughetta.dshcompose.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.fughetta.dshcompose.di.localPreferences
import java.util.Locale

/**
 * Native text in the language the app shows (设置 → 语言), not the system's.
 *
 * The choice is stored in DataStore (`zh` / `en`, absent to follow the system). `values/` holds
 * English and `values-zh/` Chinese.
 */
object AppStrings {
    /**
     * The app's name as the phone shows it (`android:label` in AndroidManifest.xml), in every
     * language: notification titles and their fallbacks.
     */
    const val APP_NAME = "DSH Compose"

    private const val PREFS = "app_language"
    private const val KEY = "language"

    @Volatile private var cache: Pair<String, Context>? = null

    /** `zh`, `en`, or null to follow the system. */
    fun language(ctx: Context): String? =
        (ctx.applicationContext ?: ctx).localPreferences(PREFS).getString(KEY, null)?.takeIf {
            it == "zh" || it == "en"
        }

    /** Stores [language] (`zh`, `en`, or null to follow the system). */
    fun setLanguage(ctx: Context, language: String?) {
        (ctx.applicationContext ?: ctx)
            .localPreferences(PREFS)
            .edit()
            .apply { if (language == null) remove(KEY) else putString(KEY, language) }
            .commit()
        cache = null
    }

    /** [base] with its resources in the app language: an activity's `attachBaseContext`. */
    fun wrap(base: Context): Context {
        val lang = language(base) ?: return base
        val locale = if (lang == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** True when native text comes out in English. */
    fun isEnglish(ctx: Context): Boolean =
        context(ctx).resources.configuration.locales[0].language != "zh"

    /** A context whose resources are in the app language. */
    fun context(ctx: Context): Context {
        val app = ctx.applicationContext ?: ctx
        val lang = language(app) ?: return app
        cache?.let { (l, c) -> if (l == lang) return c }
        val locale = if (lang == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
        return app.createConfigurationContext(config).also { cache = lang to it }
    }

    fun get(ctx: Context, @StringRes id: Int): String = context(ctx).getString(id)

    fun get(ctx: Context, @StringRes id: Int, vararg args: Any): String =
        context(ctx).getString(id, *args)

    fun plural(ctx: Context, @PluralsRes id: Int, count: Int, vararg args: Any): String =
        context(ctx).resources.getQuantityString(id, count, *args)
}
