package dev.fughetta.dshcompose.domain.plugins

import dev.fughetta.dshcompose.domain.text.LocalizedText

data class PluginDisplayMeta(
    val title: LocalizedText?,
    val description: LocalizedText?,
    val icon: String?,
    val error: String?,
)

data class PluginManagerFailure(val code: String, val message: String)

data class PluginPackageRun(
    val exitCode: Long?,
    val output: String,
    val truncated: Boolean,
    val logPath: String?,
    val kind: String?,
    val timedOut: Boolean,
)

data class PluginBundleRow(
    val rowId: String,
    val moduleName: String,
    val entryId: String? = null,
    val meta: PluginDisplayMeta? = null,
)

data class PluginBundle(
    val name: String,
    val enabled: Boolean,
    val installed: Boolean,
    val description: String?,
    val official: Boolean = false,
    val optional: Boolean = false,
    val removable: Boolean = false,
    val rows: List<PluginBundleRow> = emptyList(),
    val version: String? = null,
    val availability: String = "profile",
    val installTargetSpec: String? = null,
    val source: String? = null,
    val readOnlyReason: String? = null,
    val meta: PluginDisplayMeta? = null,
    val overrides: List<String> = emptyList(),
    val error: PluginManagerFailure? = null,
)

enum class PluginApplication(val wire: String) {
    Applied("applied"),
    RestartRequired("restart-required"),
    Failed("failed"),
    Cancelled("cancelled"),
    Overridden("overridden"),
}

data class PluginOperationResult(
    val application: PluginApplication,
    val changed: Boolean,
    val enabled: Boolean?,
    val restartReason: String?,
    val stage: String? = null,
    val target: String = "",
    val error: PluginManagerFailure? = null,
    val warnings: List<String> = emptyList(),
    val packageResult: PluginPackageRun? = null,
    val bundle: String? = null,
    val version: String? = null,
    val pendingBuilds: List<String> = emptyList(),
    val approvedBuilds: List<String> = emptyList(),
    val registries: List<String?> = emptyList(),
    val failedAt: String? = null,
) {
    val succeeded
        get() =
            application in
                setOf(
                    PluginApplication.Applied,
                    PluginApplication.RestartRequired,
                    PluginApplication.Overridden,
                )
}
