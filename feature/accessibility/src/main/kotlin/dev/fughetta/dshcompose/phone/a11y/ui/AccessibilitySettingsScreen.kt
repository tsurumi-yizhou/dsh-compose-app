package dev.fughetta.dshcompose.phone.a11y.ui

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.phone.R as PhoneR
import dev.fughetta.dshcompose.feature.accessibility.R
import kotlinx.coroutines.launch

/** State belongs to the accessibility feature, independent of the host's phone settings. */
data class AccessibilitySettingsState(
    val busy: Boolean = false,
    val enabled: Boolean = false,
    val accessibilityAttemptFailed: Boolean = false,
    val keeperGranted: Boolean = false,
    val keeperCommand: String? = null,
    val grantPath: String = "setup",
    val screenText: String? = null,
    val error: String? = null,
)

/** Host supplies navigation and privileged-channel actions without a dependency on :app. */
@Composable
fun AccessibilitySettingsScreen(
    state: AccessibilitySettingsState,
    onEnable: () -> Unit,
    onAppDetails: () -> Unit,
    onOverlayPermission: () -> Unit,
    onReadScreen: () -> Unit,
    onCloseScreen: () -> Unit,
    onGrant: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var restore by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.phone_controls_accessibility),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.phone_a11y_description))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onEnable, enabled = !state.busy) {
            Text(stringResource(R.string.phone_controls_accessibility))
        }
        AccessibilityNotes(state, onAppDetails)
        OutlinedButton(onOverlayPermission, enabled = !state.busy) {
            Text(stringResource(R.string.phone_controls_overlay_permission))
        }
        OutlinedButton({ restore = true }, enabled = !state.busy) {
            Text(stringResource(R.string.a11y_restore_title))
        }
        Button(onReadScreen, enabled = state.enabled && !state.busy) {
            Text(stringResource(R.string.phone_controls_test_screen))
        }
    }
    if (restore) A11yRestoreSheet(state, onGrant, onSetup) { restore = false }
    state.screenText?.let { ScreenTextSheet(it, onCloseScreen) }
}

/** 自动恢复无障碍: WRITE_SECURE_SETTINGS lets the app turn its accessibility service back on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun A11yRestoreSheet(
    state: AccessibilitySettingsState,
    onGrant: () -> Unit,
    onSetup: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val path = state.grantPath
    ModalBottomSheet(onClose) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.a11y_restore_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.a11y_restore_notice))
            Text(
                stringResource(R.string.a11y_restore_force_stop),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.keeperGranted)
                Text(
                    stringResource(R.string.a11y_restore_on),
                    color = MaterialTheme.colorScheme.primary,
                )
            else {
                if (path == "root") Text(stringResource(R.string.a11y_restore_root_note))
                if (path == "setup")
                    OutlinedButton(onSetup) { Text(stringResource(R.string.a11y_restore_setup)) }
                else
                    Button(
                        onGrant,
                        enabled = !state.busy,
                    ) {
                        Text(
                            stringResource(
                                when (path) {
                                    "shizuku" -> R.string.a11y_restore_grant_shizuku
                                    "shizukuPermission" ->
                                        R.string.a11y_restore_grant_shizuku_permission
                                    "adb" -> R.string.a11y_restore_grant_adb
                                    else -> R.string.a11y_restore_grant
                                },
                            ),
                        )
                    }
                state.keeperCommand?.let { command ->
                    Text(stringResource(R.string.a11y_restore_computer))
                    SelectionContainer {
                        Text(
                            command,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("", command)),
                                )
                                copied = true
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (copied) R.string.a11y_restore_copied
                                else R.string.a11y_restore_copy,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Help and recovery text under the accessibility row. */
@Composable
private fun AccessibilityNotes(
    state: AccessibilitySettingsState,
    onAppDetails: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.phone_controls_accessibility_help))
        if (state.accessibilityAttemptFailed) {
            Text(stringResource(R.string.phone_controls_restricted_help))
            TextButton(onAppDetails) {
                Text(stringResource(PhoneR.string.phone_controls_app_details))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTextSheet(text: String, onClose: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onClose) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.phone_screen_current),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text)))
                        }
                    },
                ) {
                    Icon(
                        painterResource(PhoneR.drawable.ic_content_copy),
                        stringResource(R.string.phone_screen_copy),
                    )
                }
            }
            Surface(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            ) {
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        SelectionContainer {
                            Text(
                                text.ifEmpty { stringResource(R.string.phone_screen_empty) },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.phone_screen_read_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Register this route only when the accessibility feature is included by the host. */
@Composable
fun AccessibilitySettingsRoute(viewModel: AccessibilitySettingsViewModel, onSetup: () -> Unit) {
    val state by viewModel.state.collectAsState()
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    AccessibilitySettingsScreen(
        state,
        viewModel::openAccessibilitySettings,
        viewModel::openAppDetails,
        viewModel::openOverlayPermission,
        viewModel::readScreen,
        viewModel::closeScreen,
        viewModel::grantRestorePermission,
        onSetup,
    )
}
