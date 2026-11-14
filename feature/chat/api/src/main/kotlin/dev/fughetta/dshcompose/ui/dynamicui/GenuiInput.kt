package dev.fughetta.dshcompose.ui.dynamicui

import kotlinx.serialization.json.*

data class GenuiState(
    val answers: Map<String, String> = emptyMap(),
    val multi: Map<String, List<String>> = emptyMap(),
    val fields: Map<String, String> = emptyMap(),
)

/** One interaction as the card saw it. The host re-reads the source and rebuilds the payload. */
data class GenuiInput(
    val path: String,
    val value: String? = null,
    val values: List<String> = emptyList(),
    val checked: Boolean? = null,
    val submit: Boolean = false,
    val reset: Boolean = false,
    val state: GenuiState = GenuiState(),
)
