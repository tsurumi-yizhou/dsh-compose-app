package dev.fughetta.dshcompose.ui.runtime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.ProblemPanel
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineControlsScreen(
    state: EngineControlsState,
    welcomeContent: @Composable () -> Unit = {},
    onAction: (EngineControlsAction) -> Unit,
) {
    val wide = windowTier().atLeastExpanded
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.page == EnginePage.Setup) R.string.engine_setup
                            else R.string.engine_title,
                        ),
                    )
                },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        // The control board can use the full tablet width; phones keep a readable column.
        CenteredContent(
            Modifier.fillMaxSize().padding(padding),
            maxContentWidth = if (wide) ContentWidths.Board else ContentWidths.Reading,
            horizontalPadding = Spacing.Large,
            verticalPadding = Spacing.Small,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (state.page != EnginePage.Setup)
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = Spacing.Small),
                    ) {
                        listOf(
                                EnginePage.Overview to R.string.engine_overview,
                                EnginePage.Logs to R.string.engine_logs,
                                EnginePage.Diagnostics to R.string.engine_diagnostics,
                            )
                            .forEach { (page, label) ->
                                FilterChip(
                                    state.page == page,
                                    { onAction(EngineControlsAction.Show(page)) },
                                    { Text(stringResource(label)) },
                                    Modifier.padding(end = 6.dp),
                                )
                            }
                    }
                if (state.page != EnginePage.Setup && state.busy && state.phase != "installing")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error
                    ?.takeIf { state.page != EnginePage.Setup }
                    ?.let { code ->
                        ProblemPanel(
                            title = stringResource(R.string.engine_error_title),
                            message = stringResource(R.string.engine_error_body),
                            code = code,
                            modifier = Modifier.padding(vertical = Spacing.Small),
                        )
                    }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state.page) {
                        EnginePage.Setup -> Setup(state, onAction, welcomeContent)
                        EnginePage.Logs -> Logs(state, onAction)
                        EnginePage.Diagnostics -> DiagnosticPage(state, onAction)
                        EnginePage.Overview -> Overview(state, onAction, wide)
                    }
                }
            }
        }
    }
    state.confirmation?.let { kind ->
        AlertDialog(
            onDismissRequest = { onAction(EngineControlsAction.DismissConfirmation) },
            title = {
                Text(
                    stringResource(
                        when (kind) {
                            "reset" -> R.string.engine_runtime_reset
                            "uninstall" -> R.string.engine_uninstall
                            "restore" -> R.string.engine_restore
                            "drop-kept" -> R.string.engine_drop_kept
                            else -> R.string.engine_confirm
                        },
                    ),
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            when (kind) {
                                "reset" -> R.string.engine_reset_warning
                                "uninstall" -> R.string.engine_uninstall_warning
                                "restore" -> R.string.engine_restore_warning
                                "drop-kept" -> R.string.engine_drop_kept_warning
                                else -> R.string.engine_reset_warning
                            },
                        ),
                    )
                    if (kind == "restore") RestoreDetails(state.restore)
                }
            },
            confirmButton = {
                TextButton({ onAction(EngineControlsAction.Confirm) }, enabled = !state.busy) {
                    Text(stringResource(R.string.engine_confirm))
                }
            },
            dismissButton = {
                TextButton({ onAction(EngineControlsAction.DismissConfirmation) }) {
                    Text(stringResource(R.string.engine_cancel))
                }
            },
        )
    }
}
