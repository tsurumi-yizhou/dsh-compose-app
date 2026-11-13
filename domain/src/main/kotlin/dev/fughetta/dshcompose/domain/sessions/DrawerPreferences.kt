package dev.fughetta.dshcompose.domain.sessions

import kotlinx.collections.immutable.*

enum class DrawerSort {
    Activity,
    Created,
}

data class DrawerFolder(
    val id: String,
    val name: String,
    val tint: Int? = null,
    val emoji: String? = null,
    val collapsed: Boolean = false,
    val sessionIds: PersistentList<String> = persistentListOf(),
)

data class DrawerView(
    val folders: PersistentList<DrawerFolder> = persistentListOf(),
    val sort: DrawerSort = DrawerSort.Activity,
    val createdAt: PersistentMap<String, Long> = persistentMapOf(),
) {
    val folderOf: Map<String, String>
        get() = folders.flatMap { f -> f.sessionIds.map { it to f.id } }.toMap()

    fun createFolder(folder: DrawerFolder): DrawerView {
        if (folder.id.isBlank() || folder.name.isBlank() || folders.any { it.id == folder.id })
            return this
        val ids = folder.sessionIds.toSet()
        return copy(
            folders =
                (listOf(
                        folder.copy(
                            name = cleanFolderName(folder.name),
                            sessionIds = folder.sessionIds.distinct().toPersistentList(),
                        ),
                    ) +
                        folders.map {
                            it.copy(
                                sessionIds =
                                    it.sessionIds.filterNot(ids::contains).toPersistentList(),
                            )
                        })
                    .toPersistentList(),
        )
    }

    fun updateFolder(folder: DrawerFolder): DrawerView =
        if (folder.name.isBlank()) this
        else
            copy(
                folders =
                    folders
                        .map {
                            if (it.id == folder.id)
                                folder.copy(
                                    name = cleanFolderName(folder.name),
                                    sessionIds = it.sessionIds,
                                )
                            else it
                        }
                        .toPersistentList(),
            )

    fun deleteFolder(id: String) =
        copy(folders = folders.filterNot { it.id == id }.toPersistentList())

    fun moveSessions(ids: Collection<String>, folderId: String?): DrawerView {
        if (folderId != null && folders.none { it.id == folderId }) return this
        val moving = ids.distinct()
        val set = moving.toSet()
        return copy(
            folders =
                folders
                    .map { folder ->
                        val remaining = folder.sessionIds.filterNot(set::contains)
                        folder.copy(
                            sessionIds =
                                ((if (folder.id == folderId) moving else emptyList()) + remaining)
                                    .toPersistentList(),
                        )
                    }
                    .toPersistentList(),
        )
    }

    fun reorderFolder(oldIndex: Int, newIndex: Int): DrawerView {
        if (oldIndex !in folders.indices) return this
        val rows = folders.toMutableList()
        val row = rows.removeAt(oldIndex)
        rows.add(newIndex.coerceIn(0, rows.size), row)
        return copy(folders = rows.toPersistentList())
    }

    /** Only call with the host's complete inventory, including archived conversations. */
    fun forgetMissing(known: Set<String>) =
        copy(
            createdAt = createdAt.filterKeys(known::contains).toPersistentMap(),
            folders =
                folders
                    .map {
                        it.copy(
                            sessionIds = it.sessionIds.filter(known::contains).toPersistentList(),
                        )
                    }
                    .toPersistentList(),
        )

    fun forgetSessions(ids: Set<String>) =
        copy(
            createdAt = createdAt.filterKeys { it !in ids }.toPersistentMap(),
            folders =
                folders
                    .map {
                        it.copy(
                            sessionIds = it.sessionIds.filterNot(ids::contains).toPersistentList(),
                        )
                    }
                    .toPersistentList(),
        )
}

fun cleanFolderName(name: String): String {
    val trimmed = name.trim()
    val iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    iterator.setText(trimmed)
    var end = iterator.first()
    repeat(20) {
        val next = iterator.next()
        if (next == java.text.BreakIterator.DONE) return trimmed
        end = next
    }
    return trimmed.substring(0, end)
}

data class DrawerSeen(
    val initialActivity: Long? = null,
    val seen: PersistentMap<String, Long> = persistentMapOf(),
) {
    fun markSeen(id: String, at: Long): DrawerSeen =
        if (at <= (seen[id] ?: -1)) this else copy(seen = seen.put(id, at))

    fun observe(sessions: List<SessionSummary>, viewed: String?): DrawerSeen {
        val initial = initialActivity ?: sessions.maxOfOrNull { it.updatedAt }
        val row = sessions.firstOrNull { it.sessionId == viewed }
        return copy(
            initialActivity = initial,
            seen =
                if (row != null)
                    seen.put(row.sessionId, maxOf(row.updatedAt, seen[row.sessionId] ?: 0))
                else seen,
        )
    }

    fun unread(sessions: List<SessionSummary>, current: String?): Set<String> =
        if (initialActivity == null) emptySet()
        else
            sessions
                .filter {
                    !it.running &&
                        it.sessionId != current &&
                        it.updatedAt > (seen[it.sessionId] ?: initialActivity)
                }
                .mapTo(linkedSetOf()) { it.sessionId }
}

data class DrawerPreferences(
    val view: DrawerView = DrawerView(),
    val seen: DrawerSeen = DrawerSeen(),
)

class DrawerPreferencesException(val code: String, cause: Throwable? = null) :
    Exception(code, cause)
