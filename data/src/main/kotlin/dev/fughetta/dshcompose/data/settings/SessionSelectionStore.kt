package dev.fughetta.dshcompose.data.settings

import dev.fughetta.dshcompose.domain.settings.PreferenceGroup

/** Remembers a conversation by stable host ID; history is always read from that host. */
class SessionSelectionStore(private val preferences: PreferenceGroup) {
    fun select(host: String, sessionId: String?) {
        preferences.edit().putString("session:$host", sessionId).apply()
    }

    fun forget(host: String, sessionId: String) {
        if (preferences.getString("session:$host", null) == sessionId) select(host, null)
    }

    /** Call only after the host has returned its session list successfully. */
    fun restore(host: String, knownSessions: Set<String>): String? {
        val id = preferences.getString("session:$host", null) ?: return null
        if (id in knownSessions) return id
        select(host, null)
        return null
    }
}
