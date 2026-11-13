package dev.fughetta.dshcompose.domain.settings

data class SettingsSecretSlot(
    val path: List<String>,
    val configured: Boolean,
    val credentialRef: String? = null,
    val writable: Boolean = false,
)
