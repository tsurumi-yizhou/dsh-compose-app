package dev.fughetta.dshcompose.domain.plugins

import dev.fughetta.dshcompose.domain.text.LocalizedText
import kotlinx.collections.immutable.PersistentList

data class PluginEntry(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val fiberPhase: String?,
    val patchId: String?,
    val readOnlyReason: String?,
    val meta: PluginDisplayMeta?,
)

data class PluginInspection(
    val accepted: Boolean,
    val kind: String?,
    val name: String?,
    val version: String?,
    val description: String?,
    val bundle: Boolean?,
    val registry: String?,
    val host: String?,
    val problem: String?,
    val reason: String?,
)

enum class PluginCancelStatus {
    Cancelled,
    NotRunning,
    TooLate,
}

data class PluginRegistries(
    val registry: String?,
    val fallbackRegistries: List<String>,
    val resolved: String?,
)

data class PluginMarketVersion(
    val version: String,
    val publishedSeconds: Long,
    val spec: String,
    val registry: String?,
)

data class PluginMarketEntry(
    val name: String,
    val title: LocalizedText?,
    val description: LocalizedText?,
    val icon: String?,
    val author: String?,
    val homepage: String?,
    val category: String?,
    val labels: List<String>,
    val keywords: List<String>,
    val sortKeys: Map<String, Double>,
    val versions: PersistentList<PluginMarketVersion>,
)

data class PluginMarketIndex(
    val url: String,
    val title: LocalizedText?,
    val description: LocalizedText?,
    val categories: Map<String, LocalizedText>,
    val labels: Map<String, LocalizedText>,
    val sorts: Map<String, Pair<LocalizedText, Boolean>>,
    val plugins: PersistentList<PluginMarketEntry>,
    val skipped: Int,
)
