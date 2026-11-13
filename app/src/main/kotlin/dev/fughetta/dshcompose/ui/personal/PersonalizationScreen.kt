package dev.fughetta.dshcompose.ui.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.SaveLabels
import dev.fughetta.dshcompose.ui.adaptive.SaveStatus
import dev.fughetta.dshcompose.ui.adaptive.SaveStatusBar
import dev.fughetta.dshcompose.ui.adaptive.saveShortcut

/**
 * 个人偏好: the user's own AGENTS.md; new chats follow it. Saving nothing removes the file. The save
 * action sits in the persistent [SaveStatusBar] at the bottom (Ctrl/⌘+S works too) instead of the
 * top-right corner, where a tablet with a keyboard cannot reach it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyPrefsScreen(
    state: ReplyPrefsState,
    onAction: (ReplyPrefsAction) -> Unit,
    onSaved: () -> Unit,
) {
    LaunchedEffect(Unit) { onAction(ReplyPrefsAction.Load) }
    var draft by rememberSaveable(state.loaded) { mutableStateOf(state.text) }
    val latestSaved by rememberUpdatedState(onSaved)
    var lastSavedRevision by rememberSaveable { mutableStateOf(state.savedRevision) }
    var justSaved by remember { mutableStateOf(false) }
    LaunchedEffect(state.savedRevision) {
        if (state.savedRevision != lastSavedRevision) {
            lastSavedRevision = state.savedRevision
            justSaved = true
            latestSaved()
        }
    }
    val enabled = state.loaded && state.available && !state.saving
    val dirty = enabled && draft != state.text
    val status =
        when {
            state.saving -> SaveStatus.Saving
            state.error != null -> SaveStatus.Failed
            dirty -> SaveStatus.Dirty
            justSaved -> SaveStatus.Saved
            else -> SaveStatus.Idle
        }
    val labels =
        SaveLabels(
            dirty = stringResource(R.string.reply_prefs_unsaved),
            saving = stringResource(R.string.reply_prefs_saving),
            saved = stringResource(R.string.reply_prefs_saved),
            failed = stringResource(R.string.reply_prefs_save_failed),
            save = stringResource(R.string.reply_prefs_save),
        )
    // The bar only offers save/retry while dirty or failed, so an explicit retry may always write.
    val save = { if (enabled) onAction(ReplyPrefsAction.Save(draft)) }
    Scaffold(
        modifier = Modifier.saveShortcut { save() },
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reply_prefs_title)) },
                navigationIcon = { UpButton() },
            )
        },
        bottomBar = { SaveStatusBar(status, labels, onSave = { save() }) },
    ) { padding ->
        CenteredContent(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            maxContentWidth = ContentWidths.Form,
            verticalPadding = Spacing.Large,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                Text(stringResource(R.string.reply_prefs_description))
                if (!state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.loaded && !state.available) {
                    Text(
                        stringResource(R.string.reply_prefs_unavailable),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    draft,
                    {
                        if (it.length <= ReplyPrefsState.MaxChars) {
                            draft = it
                            justSaved = false
                        }
                    },
                    Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.reply_prefs_hint)) },
                    minLines = 8,
                    keyboardOptions =
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
    }
}
