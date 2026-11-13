package dev.fughetta.dshcompose.ui.sessions

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import dev.fughetta.dshcompose.ui.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DrawerUiState(
    val loaded: Boolean = false,
    val view: DrawerView = DrawerView(),
    val seen: DrawerSeen = DrawerSeen(),
    val filter: DrawerFilter = DrawerFilter.All,
    val selection: DrawerSelection = DrawerSelection(),
    val failure: String? = null,
    val failureRevision: Long = 0,
)

/** Owns host preferences and the shared presentation of phone and wide conversation lists. */
class SessionsDrawerState(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    private val catalog: StateFlow<SessionCatalogState>,
) {
    private class Host(val id: String) {
        var load: Job? = null
    }

    private val host = MutableStateFlow<Host?>(null)

    private class Connection(val host: String, val operations: SessionsRepository)

    private val connection = MutableStateFlow<Connection?>(null)
    private val writes = Mutex()
    private val mutable = MutableStateFlow(DrawerUiState())
    val state = mutable.asStateFlow()

    private data class CreationRequest(
        val host: Host?,
        val connection: Connection?,
        val ids: Set<String>,
        val enabled: Boolean,
    )

    init {
        scope.launch {
            combine(
                    host,
                    connection,
                    catalog
                        .map { it.sessions.mapTo(linkedSetOf()) { row -> row.sessionId } }
                        .distinctUntilChanged(),
                    mutable
                        .map { it.loaded && it.view.sort == DrawerSort.Created }
                        .distinctUntilChanged(),
                    ::CreationRequest,
                )
                .distinctUntilChanged()
                .collectLatest { request ->
                    val owner = request.host ?: return@collectLatest
                    val link =
                        request.connection?.takeIf {
                            request.enabled && it.host == owner.id && it.operations.active
                        } ?: return@collectLatest
                    val operations = link.operations
                    val known = mutable.value.view.createdAt
                    val queue = ArrayDeque(request.ids.filterNot(known::containsKey))
                    coroutineScope {
                        repeat(2) {
                            launch {
                                while (queue.isNotEmpty()) {
                                    val id = queue.removeFirst()
                                    try {
                                        val at = operations.createdAt(id) ?: continue
                                        change { before ->
                                            if (
                                                host.value !== owner ||
                                                    connection.value !== link ||
                                                    !operations.active ||
                                                    before.view.sort != DrawerSort.Created ||
                                                    catalog.value.sessions.none {
                                                        it.sessionId == id
                                                    }
                                            )
                                                before
                                            else
                                                before.copy(
                                                    view =
                                                        before.view.copy(
                                                            createdAt =
                                                                before.view.createdAt.putting(
                                                                    id,
                                                                    at,
                                                                ),
                                                        ),
                                                )
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: SessionOperationException) {
                                        if (!operations.active) return@launch
                                        // Missing history leaves activity ordering as the fallback;
                                        // the data repository retains this connection's read
                                        // outcome.
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    fun connect(hostId: String, operations: SessionsRepository) {
        connection.value = Connection(hostId, operations)
    }

    fun disconnect() {
        connection.value = null
    }

    fun load(id: String) {
        host.value?.load?.cancel()
        val next = Host(id)
        mutable.value = DrawerUiState(failureRevision = mutable.value.failureRevision)
        host.value = next
        next.load = scope.launch {
            try {
                val stored = repository.watchDrawer(id).first()
                if (host.value !== next) return@launch
                mutable.update { before ->
                    before.copy(
                        loaded = true,
                        view = stored.view,
                        seen =
                            stored.seen.copy(
                                seen =
                                    (stored.seen.seen.keys + before.seen.seen.keys)
                                        .associateWith {
                                            maxOf(
                                                stored.seen.seen[it] ?: 0,
                                                before.seen.seen[it] ?: 0,
                                            )
                                        }
                                        .toPersistentMap(),
                            ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: DrawerPreferencesException) {
                if (host.value === next) {
                    mutable.update { it.copy(loaded = true) }
                    fail(error.code)
                }
            }
        }
    }

    fun mutate(transform: (DrawerView) -> DrawerView) = change {
        it.copy(view = transform(it.view))
    }

    private fun change(transform: (DrawerUiState) -> DrawerUiState) {
        val owner = host.value ?: return
        scope.launch {
            owner.load?.join()
            if (host.value !== owner) return@launch
            val before = mutable.value
            val next = transform(before)
            if (before == next) return@launch
            mutable.value = next
            save(owner)
        }
    }

    private fun save(owner: Host) {
        scope.launch {
            owner.load?.join()
            writes.withLock {
                if (host.value !== owner || !mutable.value.loaded) return@withLock
                val snapshot = mutable.value
                try {
                    repository.updateDrawer(owner.id) {
                        DrawerPreferences(snapshot.view, snapshot.seen)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: DrawerPreferencesException) {
                    if (host.value === owner) fail(error.code)
                }
            }
        }
    }

    private fun fail(code: String) {
        mutable.update { it.copy(failure = code, failureRevision = it.failureRevision + 1) }
    }

    fun markSeen(id: String, at: Long) {
        val owner = host.value ?: return
        val before = mutable.value.seen
        val next = before.markSeen(id, at)
        if (before == next) return
        mutable.update { it.copy(seen = next) }
        save(owner)
    }

    fun observeSessions(sessions: List<SessionSummary>, viewed: String?) {
        val owner = host.value ?: return
        val before = mutable.value
        if (!before.loaded) return
        val next = before.seen.observe(sessions, viewed)
        if (before.seen == next) return
        mutable.update { it.copy(seen = next) }
        save(owner)
    }

    fun handle(action: DrawerAction): Boolean {
        when (action) {
            is DrawerAction.Sort -> mutate { it.copy(sort = action.sort) }
            is DrawerAction.CreateFolder -> {
                val folder =
                    DrawerFolder(
                        java.util.UUID.randomUUID().toString(),
                        action.name,
                        action.tint?.takeIf { it in 0..7 },
                        action.emoji,
                        sessionIds = action.sessionIds.toPersistentList(),
                    )
                mutate { it.createFolder(folder) }
            }
            is DrawerAction.UpdateFolder -> mutate { it.updateFolder(action.folder) }
            is DrawerAction.DeleteFolder -> mutate { it.deleteFolder(action.id) }
            is DrawerAction.MoveSessions -> mutate { it.moveSessions(action.ids, action.folderId) }
            is DrawerAction.CollapseFolder ->
                mutate { view ->
                    view.copy(
                        folders =
                            view.folders
                                .map {
                                    if (it.id == action.id) it.copy(collapsed = action.collapsed)
                                    else it
                                }
                                .toPersistentList(),
                    )
                }
            is DrawerAction.ReorderFolder ->
                mutate { it.reorderFolder(action.oldIndex, action.newIndex) }
            is DrawerAction.Filter -> mutable.update { it.copy(filter = action.filter) }
            is DrawerAction.StartSelection ->
                mutable.update { it.copy(selection = it.selection.start(action.first)) }
            is DrawerAction.ToggleSelection ->
                mutable.update { it.copy(selection = it.selection.toggle(action.id)) }
            is DrawerAction.SelectAll ->
                mutable.update { it.copy(selection = it.selection.selectAll(action.ids)) }
            DrawerAction.EndSelection -> mutable.update { it.copy(selection = it.selection.end()) }
            else -> return false
        }
        return true
    }

    fun removeDeleted(ids: Set<String>) {
        mutable.update { before ->
            before.copy(
                selection =
                    before.selection.ids?.let {
                        DrawerSelection(it.filterNot(ids::contains).toPersistentSet())
                    } ?: before.selection,
            )
        }
        change { before ->
            before.copy(
                view = before.view.forgetSessions(ids),
                seen =
                    before.seen.copy(
                        seen = before.seen.seen.filterKeys { it !in ids }.toPersistentMap(),
                    ),
            )
        }
    }
}
