package dev.fughetta.dshcompose.ui.engine_settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.settings.lookupSetting
import dev.fughetta.dshcompose.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineSettingsScreen(
    state: EngineSettingsState,
    onAction: (EngineSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleStartEffect(Unit) {
        onAction(EngineSettingsAction.Observe(true))
        onStopOrDispose { onAction(EngineSettingsAction.Observe(false)) }
    }
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    // Matches the up button; the busy check keeps back from discarding an in-flight write.
    BackHandler(state.document != null) { if (!state.busy) onAction(EngineSettingsAction.Back) }
    Scaffold(
        modifier,
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            state.document?.let { document ->
                                document.namespace?.ns?.let(::namespaceLabel)?.let {
                                    stringResource(it)
                                } ?: document.id
                            } ?: stringResource(R.string.navigation_engine_configuration),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        UpButton(
                            if (state.document != null) {
                                { if (!state.busy) onAction(EngineSettingsAction.Back) }
                            } else null,
                        )
                    },
                    actions = {
                        ActionIcon(
                            R.drawable.ic_refresh,
                            stringResource(R.string.engine_refresh),
                            enabled = !state.busy,
                        ) {
                            onAction(EngineSettingsAction.Refresh)
                        }
                        if (state.edits.isNotEmpty())
                            TextButton(
                                onClick = { onAction(EngineSettingsAction.Save) },
                                enabled = !state.busy && !state.writeUnconfirmed,
                            ) {
                                Text(stringResource(R.string.engine_save))
                            }
                    },
                )
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding), base = 0.dp) { inset ->
            val document = state.document
            val contentPadding = PaddingValues(inset, Spacing.Small)
            if (document == null)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    messages(state)
                    if (
                        !state.loading &&
                            !state.busy &&
                            state.connected &&
                            state.scopeConfirmed &&
                            state.error == null &&
                            state.completeConfigError == null &&
                            state.namespaces.isEmpty() &&
                            state.pluginEntries.isEmpty()
                    ) {
                        item("empty") {
                            Message(stringResource(R.string.engine_configuration_empty))
                        }
                    }
                    items(state.namespaces, key = { "namespace:${it.ns}" }) { view ->
                        val label = namespaceLabel(view.ns)
                        ListItem(
                            content = {
                                Text(
                                    label?.let { stringResource(it) } ?: view.ns,
                                    fontFamily = if (label == null) FontFamily.Monospace else null,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = label?.let { { Text(view.ns) } },
                            trailingContent = { Chevron() },
                            modifier =
                                Modifier.clickable {
                                    onAction(EngineSettingsAction.OpenNamespace(view.ns))
                                },
                        )
                    }
                    items(state.pluginEntries, key = { "plugin:${it.entryId}" }) { entry ->
                        ListItem(
                            content = {
                                Text(
                                    entry.label?.resolve(locale)
                                        ?: entry.packageName
                                        ?: entry.entryId,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text(entry.reason ?: entry.state) },
                            trailingContent =
                                if (entry.configurable) {
                                    { Chevron() }
                                } else null,
                            modifier =
                                Modifier.clickable(enabled = entry.configurable) {
                                    onAction(EngineSettingsAction.OpenPlugin(entry.entryId))
                                },
                        )
                    }
                }
            else
                LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    messages(state)
                    items(
                        state.fields,
                        key = { it.key },
                        contentType = { it.node.effective.type },
                    ) { field ->
                        EngineFieldRows(
                            field = field,
                            value = state.fieldValue(field.path),
                            document = document,
                            state = state,
                            writable =
                                !state.busy &&
                                    !state.writeUnconfirmed &&
                                    !field.disabled &&
                                    document.writable &&
                                    (document.plugin == null || state.scopeConfirmed),
                            locale = locale,
                            onAction = onAction,
                        )
                    }
                }
        }
    }
    state.editor?.let { editor ->
        AlertDialog(
            onDismissRequest = { onAction(EngineSettingsAction.CloseEditor) },
            title = { Text(editor.path.joinToString(".")) },
            text = {
                Column {
                    OutlinedTextField(
                        editor.text,
                        { onAction(EngineSettingsAction.EditorText(it)) },
                        isError = editor.error != null,
                    )
                    editor.error?.let { Text(stringResource(R.string.engine_invalid_value)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { onAction(EngineSettingsAction.SaveEditor) }) {
                    Text(stringResource(R.string.engine_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(EngineSettingsAction.CloseEditor) }) {
                    Text(stringResource(R.string.engine_cancel))
                }
            },
        )
    }
    if (state.confirmDiscard)
        AlertDialog(
            onDismissRequest = { onAction(EngineSettingsAction.KeepEditing) },
            title = { Text(stringResource(R.string.engine_discard_title)) },
            text = { Text(stringResource(R.string.engine_discard_text)) },
            confirmButton = {
                TextButton(onClick = { onAction(EngineSettingsAction.Discard) }) {
                    Text(stringResource(R.string.engine_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(EngineSettingsAction.KeepEditing) }) {
                    Text(stringResource(R.string.engine_cancel))
                }
            },
        )
    state.secretPath?.let { path ->
        val input = rememberCredentialInput(documentKey(state.document), path)
        AlertDialog(
            onDismissRequest = {
                input.clearCredentialInput()
                onAction(EngineSettingsAction.CloseSecret)
            },
            title = { Text(stringResource(R.string.engine_change_secret)) },
            text = {
                Column {
                    Text(stringResource(R.string.engine_secret_storage))
                    OutlinedSecureTextField(
                        input,
                        label = { Text(stringResource(R.string.engine_secret_label)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        input.takeCredentialInput()?.let {
                            onAction(EngineSettingsAction.SaveSecret(it))
                        }
                    },
                ) {
                    Text(stringResource(R.string.engine_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            input.clearCredentialInput()
                            onAction(EngineSettingsAction.SaveSecret(null))
                        },
                    ) {
                        Text(stringResource(R.string.engine_clear_secret))
                    }
                    TextButton(
                        onClick = {
                            input.clearCredentialInput()
                            onAction(EngineSettingsAction.CloseSecret)
                        },
                    ) {
                        Text(stringResource(R.string.engine_cancel))
                    }
                }
            },
        )
    }
}

private fun documentKey(document: EngineConfigDocument?) = document?.id

private val EditableTypes =
    setOf("string", "number", "array", "dict", "tuple", "object", "union", "intersect", "bitset")

private fun LazyListScope.messages(state: EngineSettingsState) {
    state.error?.let { item("error") { Message(it, MaterialTheme.colorScheme.error) } }
    state.completeConfigError?.let {
        item("completeConfigError") { Message(it, MaterialTheme.colorScheme.error) }
    }
    state.notice?.let {
        item("notice") {
            Message(
                if (it == "READ_BEFORE_RETRY") stringResource(R.string.engine_read_again) else it,
            )
        }
    }
}

@Composable
private fun Message(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small),
        color = color,
    )
}

@Composable
private fun Chevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One field as settings rows: a headline row (title, description, current value, inherited hint,
 * trailing control) followed by one row per option for enums and bitsets.
 */
@Composable
private fun EngineFieldRows(
    field: EngineField,
    value: ToolPayload?,
    document: EngineConfigDocument,
    state: EngineSettingsState,
    writable: Boolean,
    locale: String,
    onAction: (EngineSettingsAction) -> Unit,
) {
    val input = field.node.inputNode()
    val secret = field.node.secret
    val secretCollection = field.node.containsSecret()
    val boolean = !secret && input.type == "boolean"
    val bitset = !secret && !boolean && input.type == "bitset"
    val choice = !secret && !boolean && !bitset && field.node.enumValues != null
    val plain = !secret && !boolean && !bitset && !choice
    val editable = plain && writable && !secretCollection && input.type in EditableTypes
    val resettable = !secret && !secretCollection
    val description = field.description(locale)
    val base = lookupSetting(document.base, field.path)?.takeUnless { secretCollection }
    val ref = if (secret) document.credentialRef(field.path) else null
    val slot = if (secret) document.secrets.firstOrNull { it.path == field.path } else null
    val checked = boolean && value?.primitiveContent?.toBooleanStrictOrNull() == true
    val overflow: @Composable (() -> Unit)? =
        if (resettable) {
            {
                OverflowMenu { close ->
                    if (plain)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.engine_edit)) },
                            onClick = {
                                close()
                                onAction(EngineSettingsAction.Edit(field.path))
                            },
                            enabled = editable,
                        )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.engine_reset)) },
                        onClick = {
                            close()
                            onAction(EngineSettingsAction.Unset(field.path))
                        },
                        enabled = writable,
                    )
                }
            }
        } else null
    Column {
        ListItem(
            content = { Text(field.title(locale), maxLines = 3, overflow = TextOverflow.Ellipsis) },
            supportingContent =
                if (description != null || secret || plain || base != null) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
                            description?.let { Text(it) }
                            if (secret) {
                                val configured =
                                    ref?.let { state.credentials[it]?.configured }
                                        ?: slot?.configured
                                Text(
                                    stringResource(
                                        if (configured == true) R.string.engine_secret_set
                                        else R.string.engine_secret_unset,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (plain)
                                Text(
                                    if (secretCollection)
                                        stringResource(R.string.engine_secret_collection)
                                    else
                                        (value?.primitiveContent
                                            ?: value?.toUiJson()?.toString()
                                            ?: "—"),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            base?.let {
                                Text(
                                    stringResource(
                                        R.string.engine_inherited,
                                        it.toUiJson().toString(),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else null,
            trailingContent =
                when {
                    secret -> {
                        {
                            TextButton(
                                onClick = { onAction(EngineSettingsAction.Secret(field.path)) },
                                enabled =
                                    writable &&
                                        (document.plugin == null || slot?.writable == true) &&
                                        (ref == null || state.credentials[ref]?.writable == true),
                            ) {
                                Text(stringResource(R.string.engine_change_secret))
                            }
                        }
                    }
                    boolean -> {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked, onCheckedChange = null, enabled = writable)
                                overflow?.invoke()
                            }
                        }
                    }
                    else -> overflow
                },
            modifier =
                when {
                    boolean ->
                        Modifier.toggleable(
                            checked,
                            enabled = writable,
                            role = Role.Switch,
                            onValueChange = {
                                onAction(EngineSettingsAction.Set(field.path, ToolPayload.Bool(it)))
                            },
                        )
                    plain ->
                        Modifier.clickable(
                            enabled = editable,
                            onClickLabel = stringResource(R.string.engine_edit),
                        ) {
                            onAction(EngineSettingsAction.Edit(field.path))
                        }
                    else -> Modifier
                },
        )
        if (bitset) {
            val current = value?.primitiveContent?.toLongOrNull() ?: 0L
            input.bits.forEach { (label, mask) ->
                val bit = mask.toLong()
                CheckboxListItem(
                    label,
                    (current and bit) == bit,
                    { on ->
                        onAction(
                            EngineSettingsAction.Set(
                                field.path,
                                ToolPayload.Num(
                                    (if (on) current or bit else current and bit.inv()).toString(),
                                ),
                            ),
                        )
                    },
                    enabled = writable,
                )
            }
        }
        if (choice)
            Column(Modifier.selectableGroup()) {
                field.node.enumValues.orEmpty().forEach { option ->
                    RadioListItem(
                        option.primitiveContent ?: option.toUiJson().toString(),
                        value == option,
                        { onAction(EngineSettingsAction.Set(field.path, option)) },
                        enabled = writable,
                    )
                }
            }
    }
}
