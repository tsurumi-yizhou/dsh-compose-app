package dev.fughetta.dshcompose.ui.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.feature.engine.api.R
import dev.fughetta.dshcompose.ui.extendedColors

@Composable
fun phaseColor(phase: String): Color =
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
fun phaseName(phase: String): String =
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
