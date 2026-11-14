package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.domain.providers.*

/** This state has no Saver: neither entered text nor undo history enters Activity saved state. */
@Composable
fun rememberCredentialInput(vararg keys: Any?): TextFieldState {
    val input = remember(*keys) { TextFieldState() }
    LifecycleStartEffect(input) { onStopOrDispose { input.clearCredentialInput() } }
    return input
}

@OptIn(ExperimentalFoundationApi::class)
fun TextFieldState.clearCredentialInput() {
    clearText()
    undoState.clearHistory()
}

fun TextFieldState.takeCredentialInput(): ProviderKey? {
    val key = text.takeIf { it.isNotBlank() }?.let(ProviderKey::from)
    clearCredentialInput()
    return key
}
