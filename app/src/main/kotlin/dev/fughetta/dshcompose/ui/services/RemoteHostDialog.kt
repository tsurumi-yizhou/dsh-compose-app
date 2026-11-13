package dev.fughetta.dshcompose.ui.services

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.hosts.HostPairing
import dev.fughetta.dshcompose.domain.hosts.hostServiceUrl
import dev.fughetta.dshcompose.ui.*

@Composable
fun RemoteHostDialog(state: AppState, onAction: (AppAction) -> Unit) {
    val host = state.remoteHostEditor ?: return
    var name by remember(host.id) { mutableStateOf(host.name) }
    val displayAddress = host.url.removePrefix("https://").removePrefix("http://")
    var url by remember(host.id) { mutableStateOf(displayAddress) }
    // Pairing codes never enter saved UI state or the service configuration.
    var code by remember(host.id) { mutableStateOf("") }
    val address =
        hostServiceUrl(if (url == displayAddress && host.url.isNotBlank()) host.url else url)
    val valid = name.isNotBlank() && address != null && code.length == 8
    AlertDialog(
        onDismissRequest = { if (!state.remoteHostsBusy) onAction(AppAction.CloseRemoteHost) },
        title = { Text(stringResource(R.string.compose_host_add)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.compose_host_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.remoteHostsBusy,
                    singleLine = true,
                )
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text(stringResource(R.string.compose_host_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.remoteHostsBusy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    code,
                    { code = it.filter { char -> char in '0'..'9' }.take(8) },
                    label = { Text(stringResource(R.string.compose_host_code)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.remoteHostsBusy,
                    singleLine = true,
                    visualTransformation = PairingCodeTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (url.isNotEmpty() && address == null)
                    Text(
                        stringResource(R.string.compose_host_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                state.remoteHostError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !state.remoteHostsBusy,
                onClick = {
                    address?.let {
                        onAction(
                            AppAction.SaveRemoteHost(name.trim(), HostPairing(it, code.trim())),
                        )
                    }
                    code = ""
                },
            ) {
                Text(stringResource(R.string.compose_host_connect))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.remoteHostsBusy,
                onClick = { onAction(AppAction.CloseRemoteHost) },
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private val PairingCodeTransformation = VisualTransformation { text ->
    val grouped = text.length > 4
    val display = if (grouped) text.text.take(4) + "-" + text.text.drop(4) else text.text
    TransformedText(
        AnnotatedString(display),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (grouped && offset > 4) offset + 1 else offset

            override fun transformedToOriginal(offset: Int): Int =
                if (grouped && offset > 4) offset - 1 else offset
        },
    )
}
