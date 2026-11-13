package dev.fughetta.dshcompose.domain.permissions

import kotlinx.collections.immutable.PersistentList

data class PermissionOption(val value: String, val name: String, val description: String?)

data class PermissionCatalog(
    val options: PersistentList<PermissionOption>,
    val defaultOptions: PersistentList<PermissionOption>,
    val defaultPreset: String,
)

data class PermissionDefaults(val revision: Long, val writable: Boolean, val value: String?)

data class PermissionSnapshot(
    val catalog: PermissionCatalog,
    val defaults: PermissionDefaults?,
    val current: String?,
) {
    val defaultValue
        get() = defaults?.value ?: catalog.defaultPreset
}
