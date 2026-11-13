package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.sharing.ShareFormatException
import dev.fughetta.dshcompose.domain.sharing.SharedPlugin
import dev.fughetta.dshcompose.domain.sharing.SharedSkill
import dev.fughetta.dshcompose.domain.sharing.shareLine
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

fun SharedPlugin.json() = buildJsonObject {
    put("name", name)
    put("source", source.name)
    spec?.let { put("spec", it) }
    version?.let { put("version", it) }
    integrity?.let { put("integrity", it) }
    title?.let { put("title", it) }
    author?.let { put("author", it) }
    summary?.let { put("summary", it) }
}

data class SharedPreset(val id: String, val name: String?, val bundle: String?) {
    fun json() = buildJsonObject {
        put("id", id)
        name?.let { put("name", it) }
        bundle?.let { put("bundle", it) }
    }
}

fun SharedSkill.json() = buildJsonObject {
    put("name", name)
    put("files", JsonObject(files.mapValues { JsonPrimitive(it.value) }))
}

private fun decodeSharedSkill(raw: JsonObject): SharedSkill {
    val name = raw.npString("name") ?: throw ShareFormatException("share/invalid-skill")
    val values = raw["files"] as? JsonObject ?: throw ShareFormatException("share/invalid-skill")
    val files = values.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ShareFormatException("share/invalid-skill")
    }
    return SharedSkill.checked(name, files)
}

private fun decodeSharedPlugin(raw: JsonObject): SharedPlugin =
    SharedPlugin.parse(raw.toToolPayload() as ToolPayload.Obj)

data class SharedProfile(
    val name: String? = null,
    val created: String? = null,
    val settings: PersistentMap<String, JsonObject> = persistentMapOf(),
    val plugins: PersistentList<SharedPlugin> = persistentListOf(),
    val presets: PersistentList<SharedPreset> = persistentListOf(),
    val skills: PersistentList<SharedSkill> = persistentListOf(),
    val agentsMd: String? = null,
) {
    fun json() = buildJsonObject {
        put("v", 1)
        put("kind", "profile")
        name?.let { put("name", it) }
        created?.let { put("created", it) }
        if (settings.isNotEmpty()) put("settings", JsonObject(settings))
        if (plugins.isNotEmpty()) put("plugins", JsonArray(plugins.map { it.json() }))
        if (presets.isNotEmpty()) put("presets", JsonArray(presets.map { it.json() }))
        if (skills.isNotEmpty()) put("skills", JsonArray(skills.map { it.json() }))
        agentsMd?.let { put("agentsMd", it) }
    }
}

sealed interface SharedDocument {
    val body: JsonObject

    data class Plugin(val plugin: SharedPlugin, override val body: JsonObject) : SharedDocument

    data class Profile(val profile: SharedProfile, override val body: JsonObject) : SharedDocument

    data class Conversation(val conversation: SharedConversation, override val body: JsonObject) :
        SharedDocument

    companion object {
        fun parse(raw: JsonObject): SharedDocument {
            if (raw.npString("kind") == "conversation")
                return Conversation(SharedConversation.parse(raw), raw)
            if ((raw["v"] as? JsonPrimitive)?.intOrNull != 1)
                throw ShareFormatException("share/invalid-format")
            if (raw.npString("kind") == "plugin")
                return Plugin(
                    decodeSharedPlugin(
                        raw["plugin"] as? JsonObject
                            ?: throw ShareFormatException("share/invalid-plugin"),
                    ),
                    raw,
                )
            if (raw.npString("kind") != "profile") throw ShareFormatException("share/invalid-body")
            fun objects(key: String, limit: Int): List<JsonObject> {
                val value = raw[key] ?: return emptyList()
                val list =
                    value as? JsonArray ?: throw ShareFormatException("share/invalid-profile")
                if (list.size > limit || list.any { it !is JsonObject })
                    throw ShareFormatException("share/invalid-profile")
                return list.map { it as JsonObject }
            }
            val settings =
                raw["settings"]?.let { value ->
                    val source =
                        value as? JsonObject ?: throw ShareFormatException("share/invalid-profile")
                    source
                        .mapNotNull { (ns, values) ->
                            if (
                                !ns.matches(Regex("[A-Za-z0-9._:@/-]{1,80}")) ||
                                    values !is JsonObject
                            )
                                throw ShareFormatException("share/invalid-profile")
                            if (ShareSettingsSanitizer.shareableNamespace(ns)) ns to values
                            else null
                        }
                        .toMap()
                        .toPersistentMap()
                } ?: persistentMapOf()
            val agents =
                raw.npString("agentsMd")?.also {
                    if (it.length > 64 * 1024) throw ShareFormatException("share/invalid-profile")
                }
            val profile =
                SharedProfile(
                    shareLine(raw.npString("name"), 40),
                    raw.npString("created"),
                    settings,
                    objects("plugins", 64).map { decodeSharedPlugin(it) }.toPersistentList(),
                    objects("presets", 64)
                        .map { item ->
                            val id =
                                item.npString("id")?.takeIf {
                                    it.matches(Regex("[A-Za-z0-9._-]{1,64}"))
                                } ?: throw ShareFormatException("share/invalid-profile")
                            SharedPreset(
                                id,
                                shareLine(item.npString("name"), 60),
                                item.npString("bundle"),
                            )
                        }
                        .toPersistentList(),
                    objects("skills", 32).map { decodeSharedSkill(it) }.toPersistentList(),
                    agents?.takeIf { it.isNotBlank() },
                )
            if (
                profile.plugins.map { it.name }.distinct().size != profile.plugins.size ||
                    profile.skills.map { it.name }.distinct().size != profile.skills.size ||
                    profile.presets.map { it.id }.distinct().size != profile.presets.size
            )
                throw ShareFormatException("share/invalid-profile")
            return Profile(profile, profile.json())
        }
    }
}
