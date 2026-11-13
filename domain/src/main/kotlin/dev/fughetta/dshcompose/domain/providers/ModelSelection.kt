package dev.fughetta.dshcompose.domain.providers

data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)
