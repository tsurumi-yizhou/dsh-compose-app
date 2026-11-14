package dev.fughetta.dshcompose.ui.composer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.chat.R
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun ComposerLocationDialog(onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var location by remember { mutableStateOf<Location?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) attempt++ else failed = true
        }
    LaunchedEffect(attempt) {
        if (attempt > 0) {
            loading = true
            failed = false
            location = null
            try {
                val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val provider =
                    listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                        .firstOrNull { manager.isProviderEnabled(it) }
                location =
                    if (provider == null) null
                    else
                        withTimeoutOrNull(20_000) {
                            suspendCancellableCoroutine<Location?> { continuation ->
                                val cancellation = CancellationSignal()
                                continuation.invokeOnCancellation { cancellation.cancel() }
                                LocationManagerCompat.getCurrentLocation(
                                    manager,
                                    provider,
                                    cancellation,
                                    ContextCompat.getMainExecutor(context),
                                ) { result ->
                                    if (continuation.isActive) continuation.resume(result)
                                }
                            }
                        }
                failed = location == null
            } catch (_: SecurityException) {
                failed = true
            } catch (_: IllegalArgumentException) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreDesignsystemR.string.composer_location)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.composer_location_help))
                location?.let { Text("${it.latitude}, ${it.longitude}") }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.composer_location_wait))
                }
                if (failed)
                    Text(
                        stringResource(R.string.composer_location_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                TextButton(
                    onClick = {
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                            attempt++
                        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                    enabled = !loading,
                ) {
                    Text(stringResource(R.string.composer_location_request))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { location?.let { onInsert("geo:${it.latitude},${it.longitude}") } },
                enabled = location != null && !loading,
            ) {
                Text(stringResource(R.string.composer_location_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
