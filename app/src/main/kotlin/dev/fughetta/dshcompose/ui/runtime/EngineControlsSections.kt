package dev.fughetta.dshcompose.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.extendedColors

@Composable
internal fun Action(
    label: Int,
    action: EngineControlsAction,
    onAction: (EngineControlsAction) -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val error = MaterialTheme.colorScheme.error
    OutlinedButton(
        { onAction(action) },
        Modifier.fillMaxWidth(),
        enabled = enabled,
        colors =
            if (danger) ButtonDefaults.outlinedButtonColors(contentColor = error)
            else ButtonDefaults.outlinedButtonColors(),
        border =
            if (danger && enabled) BorderStroke(1.dp, error)
            else ButtonDefaults.outlinedButtonBorder(enabled),
    ) {
        Text(stringResource(label))
    }
}

@Composable
internal fun Info(text: String) {
    Text(text, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
}

/** Secondary text that explains the control directly above or below it. */
@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun size(bytes: Long): String {
    val mib = bytes / (1024 * 1024)
    return if (mib >= 1024) "%.1f GiB".format(mib / 1024.0) else "$mib MiB"
}

@Composable
internal fun storage(state: EngineControlsState): String =
    stringResource(R.string.engine_storage, size(state.freeBytes), size(state.requiredBytes))

@Composable
internal fun Status(state: EngineControlsState) {
    val dot = phaseColor(state.phase)
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Text(
            stringResource(R.string.engine_phase, phaseName(state.phase)),
            Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    val installed = state.installedVersion
    SelectionContainer {
        Text(
            if (installed != null && installed == state.bundledVersion)
                stringResource(R.string.engine_version_bundled, installed)
            else
                stringResource(
                    R.string.engine_versions,
                    installed ?: "—",
                    state.bundledVersion ?: "—",
                ),
            Modifier.padding(vertical = 6.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    InstallationProgress(state)
}

@Composable
internal fun InstallationProgress(state: EngineControlsState) {
    if (state.phase == "installing" || state.phase == "error" && state.installStep != null) {
        val current =
            EngineInstallStages.indexOfFirst { it.first == state.installStep }.coerceAtLeast(0)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EngineInstallStages.forEachIndexed { index, (_, label) ->
                val status =
                    when {
                        index < current -> R.string.engine_install_complete
                        index > current -> R.string.engine_install_waiting
                        state.phase == "error" -> R.string.engine_install_failed
                        else -> R.string.engine_install_active
                    }
                val color =
                    when {
                        index != current -> MaterialTheme.colorScheme.onSurfaceVariant
                        state.phase == "error" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(label),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                    Text(
                        stringResource(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
            }
        }
        if (state.phase == "installing") {
            if (state.installTotal > 0)
                LinearProgressIndicator(
                    progress = {
                        (state.installDone.toFloat() / state.installTotal).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.installStep == "extract")
                Info(
                    stringResource(
                        R.string.engine_install_progress,
                        state.installDone,
                        state.installTotal,
                    ),
                )
            Info(stringResource(R.string.engine_install_background))
        }
    }
}

internal val EngineInstallStages =
    listOf(
        "extract" to R.string.engine_install_extract,
        "tools" to R.string.engine_install_tools,
        "node" to R.string.engine_install_node,
        "pnpm" to R.string.engine_install_pnpm,
        "engine" to R.string.engine_install_engine,
    )

internal fun engineInstallStageLabel(step: String?): Int =
    EngineInstallStages.firstOrNull { it.first == step }?.second ?: R.string.engine_install_extract

@Composable
internal fun phaseColor(phase: String): Color =
    when (phase) {
        "running" -> MaterialTheme.extendedColors.success
        "installing",
        "starting",
        "stopping" -> MaterialTheme.extendedColors.warning
        "notInstalled",
        "installed" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }

@Composable
internal fun phaseName(phase: String): String =
    stringResource(
        when (phase) {
            "notInstalled" -> R.string.engine_phase_missing
            "installing" -> R.string.engine_phase_installing
            "installed" -> R.string.engine_phase_ready
            "starting" -> R.string.engine_phase_starting
            "running" -> R.string.engine_phase_running
            "stopping" -> R.string.engine_phase_stopping
            else -> R.string.engine_phase_error
        },
    )
