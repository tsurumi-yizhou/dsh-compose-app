package dev.fughetta.dshcompose.appearance

import android.content.Context
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.ui.appearance.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class AppearanceStore(context: Context) {
    private val preferences = file(context)

    suspend fun load(): AppearancePreferences =
        withContext(Dispatchers.IO) {
            val raw =
                preferences.getString("preferences", null)?.let {
                    Json.parseToJsonElement(it).jsonObject
                } ?: return@withContext AppearancePreferences()
            fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
            fun JsonObject.number(key: String, default: Float) =
                (get(key) as? JsonPrimitive)?.floatOrNull?.takeIf(Float::isFinite) ?: default
            fun JsonObject.flag(key: String, default: Boolean) =
                (get(key) as? JsonPrimitive)?.booleanOrNull ?: default
            fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
            val launch =
                (raw["launch"] as? JsonObject)?.let { row ->
                    AppearanceLaunch(
                        LaunchMediaKind.entries.firstOrNull { it.name == row.string("kind") }
                            ?: return@let null,
                        row.string("path") ?: return@let null,
                        row.long("width")?.toInt() ?: return@let null,
                        row.long("height")?.toInt() ?: return@let null,
                        row.long("bytes") ?: 0,
                        row.string("poster"),
                        row.long("durationMillis"),
                        LaunchMediaFit.entries.firstOrNull { it.name == row.string("fit") }
                            ?: LaunchMediaFit.Fill,
                        LaunchMediaBackdrop.entries.firstOrNull {
                            it.name == row.string("backdrop")
                        } ?: LaunchMediaBackdrop.Auto,
                        row.long("edge")?.toInt(),
                        row.flag("enabled", true),
                    )
                }
            AppearancePreferences(
                AccentSource.entries.firstOrNull { it.name == raw.string("source") }
                    ?: AccentSource.Preset,
                raw.string("preset") ?: "mist",
                raw.long("customArgb")?.toInt() ?: 0xFF5F7A55.toInt(),
                1f,
                FeatureTileStyle.Duotone,
                launch,
                AppearanceFontFamily.System,
                1f,
            )
        }

    fun legacyWallpaperPath(): String? {
        val encoded = preferences.getString("preferences", null) ?: return null
        val raw = Json.parseToJsonElement(encoded).jsonObject
        val wallpaper = raw["wallpaper"] as? JsonObject ?: return null
        return (wallpaper["path"] as? JsonPrimitive)?.contentOrNull
    }

    suspend fun save(value: AppearancePreferences) =
        withContext(Dispatchers.IO) {
            val raw = buildJsonObject {
                put("source", value.source.name)
                put("preset", value.preset)
                put("customArgb", value.customArgb)
                value.launch?.let { media ->
                    putJsonObject("launch") {
                        put("kind", media.kind.name)
                        put("path", media.path)
                        put("width", media.width)
                        put("height", media.height)
                        put("bytes", media.bytes)
                        media.poster?.let { put("poster", it) }
                        media.durationMillis?.let { put("durationMillis", it) }
                        put("fit", media.fit.name)
                        put("backdrop", media.backdrop.name)
                        media.edge?.let { put("edge", it) }
                        put("enabled", media.enabled)
                    }
                }
            }
            if (!preferences.edit().putString("preferences", raw.toString()).commit())
                error("APPEARANCE_SAVE_FAILED")
        }

    companion object {
        fun file(context: Context) = context.applicationContext.localPreferences("dsh_appearance")

        fun notificationColor(context: Context) =
            file(context).getInt("notification_color", 0xFF4B67B4.toInt())

        fun launchTheme(context: Context): Pair<String, Int?>? {
            val raw =
                file(context).getString("preferences", null)?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject["launch"] as? JsonObject }
                        .getOrNull()
                } ?: return null
            if ((raw["enabled"] as? JsonPrimitive)?.booleanOrNull != true) return null
            return ((raw["backdrop"] as? JsonPrimitive)?.content?.lowercase() ?: "auto") to
                (raw["edge"] as? JsonPrimitive)?.intOrNull
        }
    }
}
