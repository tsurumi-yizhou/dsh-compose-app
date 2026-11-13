package dev.fughetta.dshcompose.domain.providers

data class ModelDefaults(
    val revision: Long,
    val selection: ModelSelection?,
    val rememberLastSelection: Boolean,
    val inheritedEffort: String?,
    val writable: Boolean,
)
