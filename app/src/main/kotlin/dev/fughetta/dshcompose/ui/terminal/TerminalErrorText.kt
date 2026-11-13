package dev.fughetta.dshcompose.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.R

/** Friendly text for the terminal's own error codes; engine codes fall through with the code. */
@Composable
fun terminalErrorText(code: String): String =
    when (code) {
        "terminal/input-full" -> stringResource(R.string.terminal_error_input_full)
        "terminal/unavailable" -> stringResource(R.string.terminal_error_unavailable)
        "TERMINAL_CONNECTION_FAILED" -> stringResource(R.string.terminal_error_connection)
        "TERMINAL_LINK_OPEN_FAILED" -> stringResource(R.string.terminal_error_link)
        else -> stringResource(R.string.terminal_error_unknown, code)
    }
