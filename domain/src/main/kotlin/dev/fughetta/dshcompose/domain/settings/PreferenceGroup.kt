package dev.fughetta.dshcompose.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * A typed namespace snapshot and an edit batch; storage and durable acknowledgement belong to data.
 */
class PreferenceGroup(
    val data: Flow<Map<String, Any>>,
    private val read: (String) -> Any?,
    private val write: (PreferenceChange, Boolean) -> Boolean,
) {
    fun contains(name: String) = read(name) != null

    fun getString(name: String, default: String?): String? = read(name) as String? ?: default

    fun getBoolean(name: String, default: Boolean) = read(name) as Boolean? ?: default

    fun getInt(name: String, default: Int) = read(name) as Int? ?: default

    fun getLong(name: String, default: Long) = read(name) as Long? ?: default

    fun getFloat(name: String, default: Float) = read(name) as Float? ?: default

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(name: String, default: Set<String>?): Set<String>? =
        read(name) as Set<String>? ?: default

    fun edit() = Edit()

    inner class Edit internal constructor() {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false

        fun putString(name: String, value: String?) = apply { changes[name] = value }

        fun putBoolean(name: String, value: Boolean) = apply { changes[name] = value }

        fun putInt(name: String, value: Int) = apply { changes[name] = value }

        fun putLong(name: String, value: Long) = apply { changes[name] = value }

        fun putFloat(name: String, value: Float) = apply { changes[name] = value }

        fun putStringSet(name: String, value: Set<String>?) = apply {
            changes[name] = value?.toSet()
        }

        fun remove(name: String) = apply { changes[name] = null }

        fun clear() = apply { clear = true }

        fun apply() {
            write(PreferenceChange(changes.toMap(), clear), false)
        }

        fun commit(): Boolean = write(PreferenceChange(changes.toMap(), clear), true)
    }
}

data class PreferenceChange(val values: Map<String, Any?>, val clear: Boolean)
