package dev.fughetta.dshcompose.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.R

/** Maps an engine error code or exception message to words a person can act on. */
@Composable
internal fun errorMessage(raw: String): String =
    stringResource(
        when {
            "agent-busy" in raw -> R.string.error_agent_busy
            "writer-held" in raw -> R.string.error_writer_held
            "model-unavailable" in raw || "provider-models-unavailable" in raw ->
                R.string.error_model_unavailable
            "attachment-invalid" in raw -> R.string.error_attachment_invalid
            "Unable to resolve host" in raw ||
                "failed to connect" in raw.lowercase() ||
                "Connection refused" in raw -> R.string.error_unreachable
            else -> R.string.error_unknown
        },
    )

/** Shows [raw] once per change as a dismissible snackbar, wherever the person is in the app. */
@Composable
internal fun ErrorSnackbar(raw: String?, host: SnackbarHostState) {
    val message = raw?.let { errorMessage(it) }
    val dismiss = stringResource(R.string.error_dismiss)
    LaunchedEffect(raw) {
        if (message != null) {
            android.util.Log.w("EngineError", raw.orEmpty())
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(message, dismiss, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) host.currentSnackbarData?.dismiss()
        }
    }
}
