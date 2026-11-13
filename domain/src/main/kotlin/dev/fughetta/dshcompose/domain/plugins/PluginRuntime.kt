package dev.fughetta.dshcompose.domain.plugins

import dev.fughetta.dshcompose.domain.text.LocalizedText
import kotlinx.collections.immutable.*

data class CordisPackage(
    val packageId: String,
    val name: String,
    val purpose: String,
    val hasHostHalf: Boolean,
    val hasClientHalf: Boolean,
)

data class CordisRunFailure(val phase: String, val message: String)

data class CordisRunAttempt(
    val pluginRunId: String,
    val packageId: String,
    val status: String,
    val mode: String,
    val requiresApproval: Boolean,
    val hostStatus: String?,
    val clientStatus: String?,
    val failure: CordisRunFailure?,
)

data class CordisPlugin(
    val pluginId: String,
    val agentId: String,
    val packages: PersistentList<CordisPackage>,
    val currentPackageId: String?,
    val nextPackageId: String?,
    val activeRunId: String?,
    val activePackageId: String?,
    val latestRun: CordisRunAttempt?,
) {
    val shown
        get() =
            packages.firstOrNull { it.packageId == nextPackageId }
                ?: packages.firstOrNull { it.packageId == currentPackageId }
                ?: packages.lastOrNull()

    val name
        get() = shown?.name?.takeIf(String::isNotBlank) ?: pluginId

    val purpose
        get() = shown?.purpose.orEmpty()

    val running
        get() = activeRunId != null

    val hasInterface
        get() = packages.any { it.hasClientHalf }

    val status
        get() =
            when {
                latestRun?.status == "awaiting-approval" -> "awaiting-approval"
                latestRun?.status == "failed" && latestRun.packageId == shown?.packageId -> "failed"
                running -> "running"
                latestRun?.status == "starting-host" -> "starting"
                else -> "idle"
            }
}

enum class CordisStopOutcome {
    Stopped,
    NotRunning,
    Missing,
}

data class PluginConfigEntrySummary(
    val entryId: String,
    val packageName: String?,
    val label: LocalizedText?,
    val state: String,
    val configurable: Boolean,
    val reason: String?,
    val owner: String?,
    val bundle: String?,
    val kind: String?,
    val source: String?,
    val writable: Boolean,
)
