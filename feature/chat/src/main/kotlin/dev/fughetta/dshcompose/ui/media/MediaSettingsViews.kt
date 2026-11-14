package dev.fughetta.dshcompose.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.MediaKind
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.settings.SettingsNamespace
import dev.fughetta.dshcompose.domain.settings.lookupSetting
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.RadioListItem
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.markdown.*

@Composable
internal fun ChoiceRow(
    label: String,
    values: List<String>,
    selected: String?,
    automatic: Boolean = false,
    onValue: (String?) -> Unit,
) {
    if (values.isEmpty()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            if (automatic)
                FilterChip(
                    selected == null,
                    { onValue(null) },
                    label = { Text(stringResource(R.string.media_automatic)) },
                )
            values.forEach { value ->
                FilterChip(selected == value, { onValue(value) }, label = { Text(value) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSettingsScreen(state: MediaGenerationState, onAction: (MediaAction) -> Unit) {
    val english = LocalConfiguration.current.locales[0].language == "en"
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.media_settings)) },
                    navigationIcon = { UpButton() },
                    actions = {
                        TextButton(onClick = { onAction(MediaAction.Works()) }) {
                            Text(stringResource(R.string.media_works))
                        }
                    },
                )
                if (state.busy || state.catalog.status == MediaCatalogStatus.Loading)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = ContentWidths.Form).fillMaxWidth().fillMaxHeight(),
                contentPadding = PaddingValues(Spacing.Large, Spacing.Small),
            ) {
                state.error?.let {
                    item {
                        Text(
                            it,
                            Modifier.padding(horizontal = Spacing.Large),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(state.catalog.services, key = { "${it.namespace}|${it.id}" }) { service ->
                    ListItem(
                        content = { Text(service.title) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (
                                        state.catalog.credentials[service.keyRef]?.configured ==
                                            true
                                    )
                                        R.string.media_key_configured
                                    else R.string.media_key_missing,
                                ),
                            )
                        },
                        trailingContent = {
                            Switch(
                                service.enabled,
                                { enabled ->
                                    onAction(
                                        MediaAction.WriteService(
                                            service.namespace,
                                            service.id,
                                            ToolPayload.Obj(
                                                service.raw.fields +
                                                    ("enabled" to ToolPayload.Bool(enabled)),
                                            ),
                                        ),
                                    )
                                },
                                enabled =
                                    !state.busy &&
                                        state.catalog.namespaces
                                            .find { it.ns == service.namespace }
                                            ?.writable == true,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                onAction(MediaAction.Service("${service.namespace}|${service.id}"))
                            },
                    )
                }
                item { MediaHeader(stringResource(R.string.media_add_service)) }
                items(state.catalog.presets, key = { "${it.namespace}|${it.id}" }) { preset ->
                    ListItem(
                        content = {
                            Text(if (english) preset.labelEn ?: preset.label else preset.label)
                        },
                        leadingContent = {
                            Icon(painterResource(CoreDesignsystemR.drawable.ic_add), null)
                        },
                        modifier =
                            Modifier.clickable(enabled = !state.busy) {
                                onAction(MediaAction.AddService(preset))
                            },
                    )
                }
                if (
                    state.catalog.status in
                        setOf(MediaCatalogStatus.Missing, MediaCatalogStatus.Invalid)
                )
                    item {
                        ListItem(
                            content = { Text(stringResource(R.string.media_catalog_missing)) },
                            trailingContent = {
                                TextButton(onClick = { onAction(MediaAction.ManagePlugin) }) {
                                    Text(stringResource(R.string.media_manage_plugin))
                                }
                            },
                        )
                    }
                MediaKind.entries.forEach { kind ->
                    item {
                        MediaHeader(stringResource(R.string.media_default_model, kindLabel(kind)))
                    }
                    item(key = "default:$kind") {
                        Column(Modifier.selectableGroup()) {
                            state.catalog.entries
                                .filter { it.model.kind == kind }
                                .forEach { entry ->
                                    RadioListItem(
                                        "${entry.model.label} · ${entry.service.title}",
                                        state.catalog.default(kind)?.key == entry.key,
                                        {
                                            onAction(
                                                MediaAction.Default(
                                                    entry.service.namespace,
                                                    kind,
                                                    entry.service.id,
                                                    entry.model.id,
                                                ),
                                            )
                                        },
                                        enabled = !state.busy,
                                    )
                                }
                        }
                    }
                }
                state.catalog.namespaces.forEach { namespace ->
                    item(key = "${namespace.ns}:title") { MediaHeader(namespace.ns) }
                    item { MediaHeader(stringResource(R.string.media_budget)) }
                    listOf(
                            "dailyCny" to R.string.media_daily_budget,
                            "unknownPriceHoldCny" to R.string.media_unknown_budget,
                        )
                        .forEach { (key, label) ->
                            item(key = "${namespace.ns}:budget:$key") {
                                Box(
                                    Modifier.padding(
                                        horizontal = Spacing.Large,
                                        vertical = Spacing.ExtraSmall,
                                    ),
                                ) {
                                    MediaSettingNumber(
                                        namespace,
                                        listOf("budget", key),
                                        stringResource(label),
                                        !state.busy,
                                        onAction,
                                    )
                                }
                            }
                        }
                    item { MediaHeader(stringResource(R.string.media_limits)) }
                    listOf(
                            "maxImages" to R.string.media_max_images,
                            "maxImageBytes" to R.string.media_max_image_bytes,
                            "maxResponseBytes" to R.string.media_max_response_bytes,
                            "maxTotalImageBytes" to R.string.media_max_total_bytes,
                            "maxVideoBytes" to R.string.media_max_video_bytes,
                        )
                        .forEach { (key, label) ->
                            item(key = "${namespace.ns}:limits:$key") {
                                Box(
                                    Modifier.padding(
                                        horizontal = Spacing.Large,
                                        vertical = Spacing.ExtraSmall,
                                    ),
                                ) {
                                    MediaSettingNumber(
                                        namespace,
                                        listOf("limits", key),
                                        stringResource(label),
                                        !state.busy,
                                        onAction,
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
    state.selectedService?.let { selected ->
        state.catalog.services
            .find { "${it.namespace}|${it.id}" == selected }
            ?.let { MediaServiceDialog(state, it, onAction) }
    }
}

@Composable
private fun MediaHeader(label: String) {
    Text(
        label,
        Modifier.padding(
            start = Spacing.Large,
            end = Spacing.Large,
            top = Spacing.ExtraLarge,
            bottom = Spacing.Small,
        ),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MediaServiceDialog(
    state: MediaGenerationState,
    service: MediaServiceConfig,
    onAction: (MediaAction) -> Unit,
) {
    var label by remember(service) { mutableStateOf(service.label) }
    var url by remember(service) { mutableStateOf(service.baseUrl) }
    var models by remember(service) { mutableStateOf(service.models.toSet()) }
    var provider by remember(service) { mutableStateOf(service.provider) }
    var keyRef by remember(service) { mutableStateOf(service.keyRef) }
    var responseFormat by remember(service) { mutableStateOf(service.responseFormat) }
    var secret by remember(service.id) { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val modelOptions = state.catalog.models.values.filter { it.profile == service.profile }
    AlertDialog(
        onDismissRequest = {
            secret = ""
            onAction(MediaAction.Service(null))
        },
        title = { Text(service.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    label,
                    { label = it.take(128) },
                    label = { Text(stringResource(R.string.media_service_name)) },
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    url,
                    { url = it.take(2048) },
                    label = { Text(stringResource(R.string.media_service_url)) },
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    provider,
                    { provider = it.take(64) },
                    label = { Text(stringResource(R.string.media_provider)) },
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    keyRef,
                    { keyRef = it.take(128) },
                    label = { Text(stringResource(R.string.media_credential_ref)) },
                    enabled = !state.busy,
                )
                if (service.profile == "openai-compatible")
                    ChoiceRow(
                        stringResource(R.string.media_response_format),
                        listOf("url", "b64_json"),
                        responseFormat.takeIf { it.isNotEmpty() },
                        automatic = true,
                    ) {
                        responseFormat = it.orEmpty()
                    }
                Text(stringResource(R.string.media_models))
                modelOptions.forEach { model ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            model.id in models,
                            { selected ->
                                models = if (selected) models + model.id else models - model.id
                            },
                            enabled = !state.busy,
                        )
                        Text(model.label)
                    }
                }
                Text(
                    stringResource(
                        if (state.catalog.credentials[service.keyRef]?.configured == true)
                            R.string.media_key_configured
                        else R.string.media_key_missing,
                    ),
                )
                OutlinedTextField(
                    secret,
                    { secret = it },
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.media_api_key)) },
                    singleLine = true,
                    enabled = !state.busy,
                )
                Row {
                    TextButton(
                        onClick = {
                            val key = ProviderKey.from(secret)
                            secret = ""
                            onAction(MediaAction.SetCredential(service.keyRef, key))
                        },
                        enabled = secret.isNotBlank() && !state.busy && keyRef == service.keyRef,
                    ) {
                        Text(stringResource(R.string.media_set_key))
                    }
                    TextButton(
                        onClick = { clearing = true },
                        enabled =
                            !state.busy &&
                                state.catalog.credentials[service.keyRef]?.writable == true,
                    ) {
                        Text(stringResource(R.string.media_clear_key))
                    }
                }
                TextButton(onClick = { deleting = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.media_remove_service))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    secret = ""
                    onAction(
                        MediaAction.WriteService(
                            service.namespace,
                            service.id,
                            ToolPayload.Obj(
                                service.raw.fields +
                                    mapOf(
                                        "label" to ToolPayload.Text(label),
                                        "baseUrl" to ToolPayload.Text(url),
                                        "models" to ToolPayload.Text(models.joinToString(", ")),
                                        "provider" to ToolPayload.Text(provider),
                                        "keyRef" to ToolPayload.Text(keyRef),
                                        "responseFormat" to ToolPayload.Text(responseFormat),
                                    ),
                            ),
                        ),
                    )
                    onAction(MediaAction.Service(null))
                },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.media_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    secret = ""
                    onAction(MediaAction.Service(null))
                },
            ) {
                Text(stringResource(R.string.media_cancel))
            }
        },
    )
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.media_remove_service)) },
            text = { Text(service.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        secret = ""
                        onAction(MediaAction.WriteService(service.namespace, service.id, null))
                        onAction(MediaAction.Service(null))
                    },
                ) {
                    Text(stringResource(R.string.media_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text(stringResource(R.string.media_cancel))
                }
            },
        )
    if (clearing)
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.media_clear_key)) },
            text = { Text(stringResource(R.string.media_clear_key_scope, service.keyRef)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearing = false
                        onAction(MediaAction.ClearCredential(service.keyRef))
                    },
                ) {
                    Text(stringResource(R.string.media_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) {
                    Text(stringResource(R.string.media_cancel))
                }
            },
        )
}

@Composable
private fun MediaSettingNumber(
    namespace: SettingsNamespace,
    path: List<String>,
    label: String,
    enabled: Boolean,
    onAction: (MediaAction) -> Unit,
) {
    val value = lookupSetting(namespace.value, path) ?: return
    val content =
        when (value) {
            is ToolPayload.Text -> value.value
            is ToolPayload.Num -> value.raw
            is ToolPayload.Bool -> value.value.toString()
            ToolPayload.Null -> "null"
            else -> return
        }
    var draft by remember(namespace.revision, path) { mutableStateOf(content) }
    var invalid by remember(namespace.revision, path) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        OutlinedTextField(
            draft,
            {
                draft = it.take(32)
                invalid = false
            },
            label = { Text(label) },
            isError = invalid,
            singleLine = true,
            enabled = enabled && namespace.writable,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val next =
                    if (value is ToolPayload.Text)
                        draft
                            .toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it >= 0 }
                            ?.let { ToolPayload.Text(draft) }
                    else
                        draft
                            .toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?.let { ToolPayload.Num(it.toString()) }
                if (next == null) invalid = true
                else onAction(MediaAction.WriteSetting(namespace.ns, path, next))
            },
            enabled = enabled && namespace.writable && draft != content,
        ) {
            Text(stringResource(R.string.media_save))
        }
    }
}
