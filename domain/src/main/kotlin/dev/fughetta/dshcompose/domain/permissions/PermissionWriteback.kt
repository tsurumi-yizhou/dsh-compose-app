package dev.fughetta.dshcompose.domain.permissions

data class PermissionWriteback(
    val snapshot: PermissionSnapshot? = null,
    val applied: Boolean = false,
    val failure: Exception? = null,
)
