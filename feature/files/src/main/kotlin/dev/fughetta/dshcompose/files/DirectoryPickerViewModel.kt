package dev.fughetta.dshcompose.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.workspaces.DirectoryPickerPorts
import dev.fughetta.dshcompose.domain.workspaces.DirectorySelection
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.domain.workspaces.WorkspacesRepository
import dev.fughetta.dshcompose.feature.files.R
import dev.fughetta.dshcompose.ui.DirectoryPickerState
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*

/** Lists engine paths, without resolving them against the phone's filesystem. */
class DirectoryPickerViewModel(
    parent: CoroutineScope? = null,
    private val message: (Int) -> String,
    private val publish: (DirectoryPickerState?) -> Unit,
    private val onCreated: (String) -> Unit,
    private val onWorkspace: ((Workspace) -> Unit)? = null,
    private val registerWorkspace: (suspend (WorkspacesRepository, String) -> Workspace)? = null,
    private val onDirectoryChosen: (String) -> Unit = {},
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private var owner: DirectoryPickerPorts? = null
    private var generation: Any? = null
    private var state: DirectoryPickerState? = null
    private var nonce = 0L
    private var readNumber = 0L
    private var filterNumber = 0L
    private var read: Job? = null
    private var filter: Job? = null
    private var write: Job? = null

    fun connect(ports: DirectoryPickerPorts?) {
        val next = ports?.files?.generation
        if (owner === ports && generation == next) return
        retire()
        owner = ports
        generation = next
    }

    fun open(
        path: String? = null,
        registerWorkspace: Boolean = false,
        recent: PersistentList<String> = persistentListOf(),
    ) {
        if (state?.busy == true) return
        val client = owner ?: return
        if (generation == null || client.files.generation != generation) return
        retire()
        update(
            DirectoryPickerState(
                ++nonce,
                path = path.orEmpty(),
                registerWorkspace = registerWorkspace,
                recent = recent,
            ),
        )
        browse(path)
    }

    fun editPath(value: String) {
        val dialog = state?.takeIf { !it.busy } ?: return
        if (dialog.loading) {
            readNumber++
            read?.cancel()
        }
        update(dialog.copy(path = value, loading = false, error = null))
        filterEntries()
    }

    fun browse(path: String?) {
        val dialog = state?.takeIf { !it.busy && it.newFolder == null } ?: return
        val client = owner ?: return
        val lease = generation ?: return
        if (!current(client, lease, dialog.nonce)) return
        val number = ++readNumber
        read?.cancel()
        filterNumber++
        filter?.cancel()
        update(
            dialog.copy(
                loading = true,
                path = path.orEmpty(),
                entries = persistentListOf(),
                error = null,
            ),
        )
        read = scope.launch {
            try {
                val level = client.files.browse(path)
                if (current(client, lease, dialog.nonce) && number == readNumber) {
                    update(
                        state!!.copy(level = level, path = level.path, loading = false, query = ""),
                    )
                    filterEntries()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, lease, dialog.nonce) && number == readNumber)
                    update(
                        state!!.copy(
                            loading = false,
                            error = failure(error, R.string.directory_read_failed),
                        ),
                    )
            }
        }
    }

    fun query(value: String) {
        state?.let {
            update(it.copy(query = value))
            filterEntries()
        }
    }

    fun hidden(value: Boolean) {
        state?.let {
            update(it.copy(showHidden = value))
            filterEntries()
        }
    }

    private fun filterEntries() {
        val dialog = state ?: return
        val level = dialog.level ?: return
        val number = ++filterNumber
        filter?.cancel()
        filter = scope.launch {
            val entries =
                withContext(Dispatchers.Default) {
                    val query = dialog.query.trim()
                    level.entries
                        .filter {
                            (dialog.showHidden || !it.hidden) &&
                                it.name.contains(query, ignoreCase = true)
                        }
                        .toPersistentList()
                }
            if (state?.nonce == dialog.nonce && filterNumber == number && state?.level === level)
                update(state!!.copy(entries = entries))
        }
    }

    fun openCreate() {
        state?.takeIf { it.canChoose }?.let { update(it.copy(newFolder = "", error = null)) }
    }

    fun folderName(value: String) {
        state
            ?.takeIf { !it.busy && it.newFolder != null }
            ?.let { update(it.copy(newFolder = value, error = null)) }
    }

    fun closeCreate() {
        state?.takeIf { !it.busy }?.let { update(it.copy(newFolder = null, error = null)) }
    }

    fun create() {
        val dialog = state?.takeIf { !it.busy && it.canCreateFolder } ?: return
        val level = dialog.level ?: return
        val client = owner ?: return
        val lease = generation ?: return
        if (!current(client, lease, dialog.nonce)) return
        update(dialog.copy(busy = true, error = null))
        write = scope.launch {
            try {
                val path = client.files.createDirectory(level.path, dialog.newFolder!!)
                if (!current(client, lease, dialog.nonce)) return@launch
                update(state!!.copy(busy = false, newFolder = null))
                browse(path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, lease, dialog.nonce))
                    update(
                        state!!.copy(
                            busy = false,
                            error = failure(error, R.string.directory_create_failed),
                        ),
                    )
            }
        }
    }

    fun choose() {
        val dialog = state?.takeIf { it.canChoose } ?: return
        val path = dialog.level!!.path
        val client = owner ?: return
        val lease = generation ?: return
        if (!current(client, lease, dialog.nonce)) return
        update(dialog.copy(busy = true, error = null))
        write = scope.launch {
            try {
                val result =
                    client.workspaces.chooseDirectory(
                        client.files,
                        client.sessions,
                        path,
                        dialog.registerWorkspace,
                        registerWorkspace,
                    )
                if (!current(client, lease, dialog.nonce)) return@launch
                when (result) {
                    is DirectorySelection.Registered -> {
                        update(null)
                        onWorkspace?.invoke(result.workspace)
                    }
                    is DirectorySelection.Conversation -> {
                        onDirectoryChosen(path)
                        update(null)
                        onCreated(result.id)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, lease, dialog.nonce))
                    update(
                        state!!.copy(
                            busy = false,
                            error =
                                failure(
                                    error,
                                    if (dialog.registerWorkspace) R.string.workspace_add_failed
                                    else R.string.directory_session_failed,
                                ),
                        ),
                    )
            }
        }
    }

    fun dismiss() {
        if (state?.busy != true) retire()
    }

    private fun failure(error: Exception, fallback: Int) =
        message(
            when (operationErrorCode(error)) {
                "directory-picker/unreadable" -> R.string.directory_unreadable
                "directory-picker/unavailable" -> R.string.directory_unavailable
                "directory-picker/exists" -> R.string.directory_exists
                else -> fallback
            },
        )

    private fun current(client: DirectoryPickerPorts, lease: Any?, own: Long) =
        owner === client &&
            generation == lease &&
            client.files.generation == lease &&
            state?.nonce == own

    private fun update(value: DirectoryPickerState?) {
        state = value
        publish(value)
    }

    private fun retire() {
        nonce++
        readNumber++
        filterNumber++
        read?.cancel()
        filter?.cancel()
        write?.cancel()
        update(null)
    }

    override fun onCleared() {
        close()
    }

    fun close() {
        retire()
        owner = null
        generation = null
    }
}
