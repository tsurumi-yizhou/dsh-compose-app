package dev.fughetta.dshcompose.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

/** A navigation request is consumed once across Activity recreation. */
@Composable
internal fun NavigationEvent(revision: Long, action: () -> Unit) {
    var consumed by rememberSaveable { mutableLongStateOf(0L) }
    val latestAction by rememberUpdatedState(action)
    LaunchedEffect(revision) {
        if (revision != consumed) {
            consumed = revision
            if (revision > 0) latestAction()
        }
    }
}
