package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.sharing.ShareFormatException
import dev.fughetta.dshcompose.domain.sharing.SharedPlugin
import dev.fughetta.dshcompose.domain.sharing.SharedPluginSource
import dev.fughetta.dshcompose.domain.sharing.SharedSkill
import dev.fughetta.dshcompose.domain.sharing.sharedPluginFromBundle
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

enum class SharedSection {
    Plugins,
    Presets,
    Settings,
    Skills,
    Agents,
}

data class SharedExportItem(
    val id: String,
    val section: SharedSection,
    val title: String,
    val detail: String? = null,
    val bytes: Int = 0,
    val blocked: String? = null,
    val defaultOn: Boolean = true,
)

data class SharedProfileSource(
    val profile: SharedProfile,
    val items: PersistentList<SharedExportItem>,
) {
    fun build(selected: Set<String>, name: String): SharedProfile {
        val plugins = profile.plugins.filter { "plugin:${it.name}" in selected }.toPersistentList()
        val names = plugins.map { it.name }.toSet()
        return profile.copy(
            name = name.trim().take(40).takeIf { it.isNotEmpty() },
            created = java.time.Instant.now().toString(),
            settings = profile.settings.filterKeys { "settings:$it" in selected }.toPersistentMap(),
            plugins = plugins,
            presets = profile.presets.filter { it.bundle in names }.toPersistentList(),
            skills = profile.skills.filter { "skill:${it.name}" in selected }.toPersistentList(),
            agentsMd = profile.agentsMd?.takeIf { "agents-md" in selected },
        )
    }
}

suspend fun ProtocolClient.shareProfileSource(
    locale: String,
    skills: List<SharedSkill> = emptyList(),
    agents: String? = null,
): SharedProfileSource = coroutineScope {
    val settingsJob = async { describeSettings() }
    val bundlesJob = async { listBundles() }
    val presetJob = async { agentPresets() }
    val settings = settingsJob.await()
    val bundles = bundlesJob.await()
    val presets = presetJob.await()
    val items = mutableListOf<SharedExportItem>()
    val sections = linkedMapOf<String, JsonObject>()
    val plugins = mutableListOf<SharedPlugin>()
    val includedPresets = mutableListOf<SharedPreset>()
    val includedSkills = mutableListOf<SharedSkill>()
    for (view in settings.namespaces) {
        val sanitized = ShareSettingsSanitizer.namespace(view) ?: continue
        if (sanitized.values.isEmpty()) continue
        sections[view.ns] = sanitized.values
        items +=
            SharedExportItem(
                "settings:${view.ns}",
                SharedSection.Settings,
                view.ns,
                if (sanitized.dropped > 0) "${sanitized.dropped}" else null,
                sanitized.values.toString().toByteArray().size,
            )
    }
    for (bundle in bundles) {
        if (
            bundle.official && !(bundle.installed || bundle.optional && bundle.enabled) ||
                !bundle.official && !bundle.installed
        )
            continue
        val shared = sharedPluginFromBundle(bundle, locale)
        if (shared != null) {
            plugins += shared
            bundle.rows
                .filter { it.moduleName == AGENT_PRESET_MODULE && it.rowId.startsWith("preset-") }
                .forEach { row ->
                    val id = row.rowId.removePrefix("preset-")
                    includedPresets +=
                        SharedPreset(id, presets.find { it.id == id }?.name, bundle.name)
                }
        }
        items +=
            SharedExportItem(
                "plugin:${bundle.name}",
                SharedSection.Plugins,
                shared?.displayTitle ?: bundle.name,
                shared?.spec ?: bundle.version,
                shared?.json()?.toString()?.toByteArray()?.size ?: 0,
                if (shared == null) "share/local-plugin" else null,
            )
    }
    for (skill in skills) {
        val secret = skill.files.values.any { ShareSettingsSanitizer.containsSecret(it) }
        if (!secret) includedSkills += skill
        items +=
            SharedExportItem(
                "skill:${skill.name}",
                SharedSection.Skills,
                skill.name,
                bytes = skill.json().toString().toByteArray().size,
                blocked = if (secret) "share/contains-secret" else null,
            )
    }
    val safeAgents = agents?.takeUnless(ShareSettingsSanitizer::containsSecret)
    if (agents != null)
        items +=
            SharedExportItem(
                "agents-md",
                SharedSection.Agents,
                "AGENTS.md",
                bytes = agents.toByteArray().size,
                blocked = if (safeAgents == null) "share/contains-secret" else null,
            )
    SharedProfileSource(
        SharedProfile(
            settings = sections.toPersistentMap(),
            plugins = plugins.toPersistentList(),
            presets = includedPresets.toPersistentList(),
            skills = includedSkills.toPersistentList(),
            agentsMd = safeAgents,
        ),
        items.toPersistentList(),
    )
}

enum class SharedChangeKind {
    Install,
    Replace,
    Enable,
    Add,
    Change,
    Unchanged,
    Unavailable,
}

data class SharedImportChange(
    val id: String,
    val section: SharedSection,
    val title: String,
    val kind: SharedChangeKind,
    val detail: String? = null,
    val sensitive: Boolean = false,
    val defaultOn: Boolean = true,
    val blocked: String? = null,
    val follows: String? = null,
    val plugin: SharedPlugin? = null,
    val spec: String? = null,
    val values: JsonObject? = null,
    val ops: List<SettingsOp> = emptyList(),
    val skill: SharedSkill? = null,
    val text: String? = null,
) {
    val actionable
        get() =
            blocked == null &&
                kind !in setOf(SharedChangeKind.Unchanged, SharedChangeKind.Unavailable)
}

data class SharedImportPlan(val changes: PersistentList<SharedImportChange>, val profile: Boolean)

/** Leaf writes preserve the target's unrelated values; credentials cannot be imported. */
fun shareSettingsChanges(
    namespace: String,
    values: JsonObject,
    view: SettingsNamespaceView?,
): Pair<List<SettingsOp>, Boolean> {
    val ops = mutableListOf<SettingsOp>()
    var sensitive =
        namespace.startsWith("llm-") ||
            namespace in setOf("permission", "bash-sandbox", "pwsh-sandbox")
    val secretPaths = view?.secrets?.map { it.path }?.toSet().orEmpty()
    fun containsSecret(value: JsonElement, schema: SchemaNode?, path: List<String>): Boolean {
        if (schema?.secret == true || path in secretPaths) return true
        return when (value) {
            is JsonObject ->
                value.any { (name, item) ->
                    schema?.fields()?.get(name)?.credentialRef != true &&
                        ShareSettingsSanitizer.secretName(name) ||
                        containsSecret(
                            item,
                            schema?.fields()?.get(name)
                                ?: if (schema?.effective?.type == "dict") schema.inner else null,
                            path + name,
                        )
                }
            is JsonArray ->
                value.withIndex().any { (index, item) ->
                    containsSecret(item, schema?.inner, path + index.toString())
                }
            else -> false
        }
    }
    fun visit(value: JsonElement, schema: SchemaNode?, path: List<String>) {
        if (
            schema?.secret == true ||
                path in secretPaths ||
                schema?.credentialRef != true && ShareSettingsSanitizer.secretName(path.last())
        )
            return
        if (value is JsonObject)
            value.forEach { (key, item) ->
                visit(
                    item,
                    schema?.fields()?.get(key)
                        ?: if (schema?.effective?.type == "dict") schema.inner else null,
                    path + key,
                )
            }
        else if (
            !containsSecret(value, schema, path) && value != lookupSetting(view?.value, path)
        ) {
            ops += SettingsOp(path, value)
            if (
                schema?.credentialRef == true ||
                    path.any {
                        Regex("endpoint|baseurl|url|host|proxy", RegexOption.IGNORE_CASE)
                            .containsMatchIn(it)
                    } ||
                    value.toString().contains("https://") ||
                    value.toString().contains("http://")
            )
                sensitive = true
        }
    }
    values.forEach { (key, value) -> visit(value, view?.schema?.fields()?.get(key), listOf(key)) }
    return ops to sensitive
}

suspend fun ProtocolClient.planSharedImport(
    document: SharedDocument,
    safeMode: Boolean,
    local: Boolean,
    localSkills: Map<String, SharedSkill> = emptyMap(),
    agents: String? = null,
    npmFacts: Map<String, SharedNpmFacts> = emptyMap(),
): SharedImportPlan = coroutineScope {
    val profile = (document as? SharedDocument.Profile)?.profile
    val plugins =
        profile?.plugins
            ?: (document as? SharedDocument.Plugin)?.let { persistentListOf(it.plugin) }
            ?: throw ShareFormatException("share/not-installable")
    val bundles = listBundles()
    val changes = mutableListOf<SharedImportChange>()
    for (plugin in plugins) {
        val current = bundles.find { it.name == plugin.name }
        val official = plugin.source == SharedPluginSource.official
        val onDemand =
            official &&
                current?.installed == false &&
                current.availability == "missing" &&
                current.installTargetSpec != null
        val kind =
            when {
                official && current == null -> SharedChangeKind.Unavailable
                onDemand -> SharedChangeKind.Install
                official && current?.enabled == true -> SharedChangeKind.Unchanged
                official -> SharedChangeKind.Enable
                current?.installed == true &&
                    plugin.version != null &&
                    plugin.version == current.version -> SharedChangeKind.Unchanged
                current?.installed == true -> SharedChangeKind.Replace
                else -> SharedChangeKind.Install
            }
        val facts = npmFacts[plugin.name]
        val blocked =
            when {
                kind == SharedChangeKind.Unavailable -> "share/plugin-unavailable"
                kind == SharedChangeKind.Unchanged -> null
                plugin.integrity != null &&
                    facts?.integrity != null &&
                    plugin.integrity != facts.integrity -> "share/package-changed"
                safeMode -> "share/safe-mode"
                kind != SharedChangeKind.Enable && !connection.supportsBundleRemoval ->
                    "share/install-unavailable"
                else -> null
            }
        val detail =
            listOfNotNull(
                    listOfNotNull(current?.version, plugin.version ?: plugin.spec)
                        .joinToString(" → ")
                        .takeIf { it.isNotBlank() },
                    facts?.publisher,
                    facts?.size?.let { "${it / 1024 + 1} KiB" },
                )
                .joinToString(" · ")
        changes +=
            SharedImportChange(
                "plugin:${plugin.name}",
                SharedSection.Plugins,
                plugin.displayTitle,
                kind,
                detail,
                blocked = blocked,
                plugin = plugin,
                spec = if (onDemand) current.installTargetSpec else plugin.spec,
            )
    }
    if (profile != null) {
        val presets = agentPresets().map { it.id }.toSet()
        profile.presets
            .filter { it.id !in presets }
            .forEach { preset ->
                val owner = changes.find { it.id == "plugin:${preset.bundle}" && it.actionable }
                if (owner != null)
                    changes +=
                        SharedImportChange(
                            "preset:${preset.id}",
                            SharedSection.Presets,
                            preset.name ?: preset.id,
                            SharedChangeKind.Add,
                            follows = owner.id,
                        )
            }
        val description = describeSettings()
        val views = description.namespaces.associateBy { it.ns }
        for ((namespace, values) in profile.settings) {
            val view = views[namespace]
            val (ops, sensitive) = shareSettingsChanges(namespace, values, view)
            if (ops.isEmpty()) continue
            val delayed = changes.any { it.section == SharedSection.Plugins && it.actionable }
            val blocked =
                when {
                    !description.writable || view?.writable == false -> "share/settings-read-only"
                    view == null && !delayed -> "share/settings-unavailable"
                    else -> null
                }
            changes +=
                SharedImportChange(
                    "settings:$namespace",
                    SharedSection.Settings,
                    namespace,
                    if (view == null) SharedChangeKind.Add else SharedChangeKind.Change,
                    detail =
                        ops.joinToString("\n") {
                            "${it.path.joinToString(".")}: ${lookupSetting(view?.value, it.path) ?: "∅"} → ${it.value}"
                        },
                    sensitive = sensitive,
                    defaultOn = !sensitive,
                    blocked = blocked,
                    values = values,
                    ops = ops,
                )
        }
        profile.skills.forEach { skill ->
            val current = localSkills[skill.name]
            changes +=
                SharedImportChange(
                    "skill:${skill.name}",
                    SharedSection.Skills,
                    skill.name,
                    if (current == skill) SharedChangeKind.Unchanged
                    else if (current == null) SharedChangeKind.Add else SharedChangeKind.Replace,
                    defaultOn = current == null,
                    blocked = if (!local) "share/local-only" else null,
                    skill = skill,
                )
        }
        profile.agentsMd?.let { text ->
            changes +=
                SharedImportChange(
                    "agents-md",
                    SharedSection.Agents,
                    "AGENTS.md",
                    if (text == agents) SharedChangeKind.Unchanged
                    else if (agents == null) SharedChangeKind.Add else SharedChangeKind.Replace,
                    detail = text,
                    sensitive = true,
                    defaultOn = agents == null,
                    blocked = if (!local) "share/local-only" else null,
                    text = text,
                )
        }
    }
    SharedImportPlan(changes.toPersistentList(), profile != null)
}
