package dev.fughetta.dshcompose.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.domain.links.workspaceParentPath
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFileChange
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.ui.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

class WorkspaceFilesViewModel(
    parent: CoroutineScope? = null,
    private val message: (Int) -> String,
    private val previewDirectory: File? = null,
    private val publish: (WorkspaceFilesUiState?) -> Unit,
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private var owner: WorkspaceFilesRepository? = null
    private var generation: Any? = null
    private var epoch = 0L
    private var readNumber = 0L
    private var previewNumber = 0L
    private var read: Job? = null
    private var previewRead: Job? = null
    private var directoryWatch: Job? = null
    private var fileWatch: Job? = null
    private var directoryRefresh: Job? = null
    private var directoryPending = false
    private var directoryWatchNumber = 0L
    private var verifyAfterRead = false
    private var observing = false
    private var searchRead: Job? = null
    private var searchNumber = 0L
    private var previewFile: File? = null
    private var state: WorkspaceFilesUiState? = null

    fun connect(files: WorkspaceFilesRepository?) {
        val lease = files?.generation
        if (owner === files && generation == lease) return
        dismiss()
        owner = files
        generation = lease
    }

    fun open(sessionId: String, file: String? = null) {
        val client = owner ?: return
        if (generation == null || client.generation != generation) return
        dismiss()
        update(WorkspaceFilesUiState(sessionId))
        browse(file?.let(::workspaceParentPath).orEmpty())
        if (file != null) preview(file)
    }

    fun browse(path: String) {
        searchNumber++
        searchRead?.cancel()
        readNumber++
        read?.cancel()
        read = null
        directoryPending = false
        directoryWatch?.cancel()
        directoryRefresh?.cancel()
        closePreview()
        state?.let {
            update(
                it.copy(
                    path = path,
                    listing = null,
                    loading = false,
                    watchError = null,
                    search = WorkspaceFileSearch(),
                ),
            )
        }
        refreshDirectory()
        if (observing) watchDirectory()
    }

    fun refresh() {
        val view = state ?: return
        if (view.search.query.isNotBlank()) search(view.search.query, immediate = true)
        else browse(view.path)
    }

    fun search(query: String, immediate: Boolean = false) {
        val browser = state ?: return
        val client = owner ?: return
        val own = epoch
        val number = ++searchNumber
        searchRead?.cancel()
        update(browser.copy(search = WorkspaceFileSearch(query, loading = query.isNotBlank())))
        if (query.isBlank()) {
            refreshDirectory()
            return
        }
        searchRead = scope.launch {
            try {
                val results =
                    client.referenceMatches(browser.sessionId, query, false, immediate).files
                if (current(client, own) && searchNumber == number)
                    update(state!!.copy(search = WorkspaceFileSearch(query, results)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && searchNumber == number)
                    update(
                        state!!.copy(
                            search =
                                WorkspaceFileSearch(
                                    query,
                                    error = message(UiR.string.workspace_files_search_failed),
                                ),
                        ),
                    )
            }
        }
    }

    private fun refreshDirectory() {
        if (read?.isActive == true) {
            directoryPending = true
            return
        }
        val view = state ?: return
        val path = view.path
        val client = owner ?: return
        val own = epoch
        val number = ++readNumber
        update(view.copy(loading = true, error = null))
        read =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val listing = client.listing(view.sessionId, path)
                    if (current(client, own) && number == readNumber)
                        update(
                            state!!.copy(listing = listing, path = listing.path, loading = false),
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (current(client, own) && number == readNumber)
                        update(
                            state!!.copy(
                                loading = false,
                                error = message(UiR.string.workspace_files_read_failed),
                            ),
                        )
                } finally {
                    if (current(client, own) && number == readNumber) {
                        read = null
                        if (directoryPending) {
                            directoryPending = false
                            refreshDirectory()
                        }
                    }
                }
            }
        read!!.start()
    }

    fun preview(path: String) {
        if (state == null) return
        closePreview()
        val extension = path.substringAfterLast('.', "").lowercase()
        val kind =
            when (extension) {
                "pdf" -> WorkspacePreviewKind.Pdf
                "doc",
                "docx",
                "xls",
                "xlsx",
                "ppt",
                "pptx" -> WorkspacePreviewKind.Office
                "png",
                "jpg",
                "jpeg",
                "webp",
                "gif",
                "bmp",
                "heif",
                "heic",
                "avif" -> WorkspacePreviewKind.Image
                else -> WorkspacePreviewKind.Text
            }
        update(state!!.copy(preview = WorkspaceFilePreview(path, kind = kind)))
        if (observing) watchPreview()
        if (kind != WorkspacePreviewKind.Text) loadBinaryPreview() else loadPage(1, reload = true)
    }

    fun page(next: Boolean) {
        val view = state?.preview?.takeIf { !it.loading && !it.stale } ?: return
        val page = view.page ?: return
        if (next) {
            if (!page.eof && page.count > 0) loadPage(page.offset + page.count, reload = false)
        } else view.previous.lastOrNull()?.let { loadPage(it, reload = false) }
    }

    fun reloadPreview() {
        if (state?.preview?.loading != false) return
        if (observing && fileWatch?.isActive != true) watchPreview()
        if (state?.preview?.kind != WorkspacePreviewKind.Text) loadBinaryPreview()
        else loadPage(1, reload = true)
    }

    private fun loadBinaryPreview(verification: Boolean = false) {
        val browser = state ?: return
        val view = browser.preview ?: return
        val client = owner ?: return
        val own = epoch
        val number = ++previewNumber
        previewRead?.cancel()
        update(browser.copy(preview = view.copy(loading = true, error = null)))
        previewRead = scope.launch {
            var target: File? = null
            var retained = false
            try {
                if (
                    verification &&
                        view.kind == WorkspacePreviewKind.Office &&
                        view.fileVersion != null
                ) {
                    val stat = client.stat(browser.sessionId, view.path)
                    if (!current(client, own) || number != previewNumber) return@launch
                    val changed = stat.version != view.fileVersion
                    update(
                        state!!.copy(
                            preview =
                                view.copy(
                                    loading = false,
                                    stale = changed,
                                    error =
                                        if (changed) message(UiR.string.workspace_file_changed)
                                        else null,
                                    watchError = state?.preview?.watchError,
                                ),
                        ),
                    )
                    return@launch
                }
                val cached =
                    client.preview(
                        previewDirectory?.absolutePath,
                        browser.sessionId,
                        view.path,
                        renderPdf = view.kind == WorkspacePreviewKind.Office,
                        maximumBytes =
                            (if (view.kind == WorkspacePreviewKind.Pdf) 64L else 20L) * 1024 * 1024,
                    )
                target = File(cached.path)
                val receipt = cached.receipt
                val missingFonts = cached.missingFonts
                if (!current(client, own) || number != previewNumber) return@launch
                if (
                    verification && view.fileVersion != null && receipt.version != view.fileVersion
                ) {
                    update(
                        state!!.copy(
                            preview =
                                view.copy(
                                    loading = false,
                                    stale = true,
                                    error = message(UiR.string.workspace_file_changed),
                                ),
                        ),
                    )
                    return@launch
                }
                previewFile?.delete()
                previewFile = target
                retained = true
                update(
                    state!!.copy(
                        preview =
                            view.copy(
                                cachePath = target!!.absolutePath,
                                fileVersion = receipt.version,
                                loading = false,
                                stale = false,
                                error = null,
                                watchError = state?.preview?.watchError,
                                missingFonts = missingFonts,
                            ),
                    ),
                )
                if (verifyAfterRead) {
                    verifyAfterRead = false
                    if (observing) loadBinaryPreview(verification = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val text =
                    when {
                        operationErrorCode(error) == "workspace-file/changed" ->
                            UiR.string.workspace_file_changed
                        operationErrorCode(error) == "workspace-file/too-large" ->
                            if (view.kind == WorkspacePreviewKind.Image)
                                UiR.string.workspace_image_too_large
                            else UiR.string.workspace_pdf_too_large
                        view.kind == WorkspacePreviewKind.Office ->
                            UiR.string.workspace_office_failed
                        else -> UiR.string.workspace_files_read_failed
                    }
                if (current(client, own) && number == previewNumber)
                    update(
                        state!!.copy(
                            preview =
                                view.copy(
                                    loading = false,
                                    stale =
                                        view.stale ||
                                            operationErrorCode(error) == "workspace-file/changed",
                                    error = message(text),
                                ),
                        ),
                    )
            } finally {
                if (!retained) target?.delete()
            }
        }
    }

    private fun loadPage(offset: Int, reload: Boolean, verification: Boolean = false) {
        val browser = state ?: return
        val view = browser.preview ?: return
        val previousPage = view.page
        val client = owner ?: return
        val own = epoch
        val number = ++previewNumber
        previewRead?.cancel()
        update(browser.copy(preview = view.copy(loading = true, error = null)))
        previewRead = scope.launch {
            try {
                val page = client.readText(browser.sessionId, view.path, offset)
                if (!current(client, own) || previewNumber != number) return@launch
                if (!reload && previousPage != null && page.version != previousPage.version) {
                    update(
                        state!!.copy(
                            preview =
                                view.copy(
                                    loading = false,
                                    stale = true,
                                    error = message(UiR.string.workspace_file_changed),
                                ),
                        ),
                    )
                    return@launch
                }
                val previous =
                    when {
                        reload -> kotlinx.collections.immutable.persistentListOf()
                        verification -> view.previous
                        offset == view.previous.lastOrNull() ->
                            view.previous.removingAt(view.previous.lastIndex)
                        else -> view.previous.adding(view.page!!.offset)
                    }
                update(
                    state!!.copy(
                        preview =
                            view.copy(
                                page = page,
                                previous = previous,
                                loading = false,
                                stale = false,
                                error = null,
                                watchError = state?.preview?.watchError,
                            ),
                    ),
                )
                if (verifyAfterRead) {
                    verifyAfterRead = false
                    if (observing) loadPage(page.offset, reload = false, verification = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val text =
                    message(
                        when (operationErrorCode(error)) {
                            "workspace-file/not-text" -> UiR.string.workspace_file_not_text
                            "workspace-file/too-large" -> UiR.string.workspace_file_page_too_large
                            else -> UiR.string.workspace_files_read_failed
                        },
                    )
                if (current(client, own) && previewNumber == number)
                    update(state!!.copy(preview = view.copy(loading = false, error = text)))
            }
        }
    }

    fun observe(value: Boolean) {
        observing = value
        directoryWatchNumber++
        directoryWatch?.cancel()
        fileWatch?.cancel()
        directoryRefresh?.cancel()
        if (value) {
            watchDirectory()
            watchPreview()
            state?.search?.query?.takeIf { it.isNotBlank() }?.let { search(it) }
        } else {
            searchNumber++
            searchRead?.cancel()
            state?.let { update(it.copy(search = it.search.copy(loading = false))) }
        }
    }

    private fun watchDirectory() {
        val view = state ?: return
        val client = owner ?: return
        val own = epoch
        val path = view.path
        val watchNumber = ++directoryWatchNumber
        directoryWatch = scope.launch {
            try {
                client.changes(view.sessionId, path).collect {
                    if (!current(client, own) || directoryWatchNumber != watchNumber) return@collect
                    update(state!!.copy(watchError = null))
                    if (directoryRefresh?.isActive != true)
                        directoryRefresh = scope.launch {
                            delay(150)
                            if (current(client, own) && directoryWatchNumber == watchNumber) {
                                val query = state!!.search.query
                                if (query.isBlank()) refreshDirectory()
                                else search(query, immediate = true)
                            }
                        }
                }
                if (current(client, own) && directoryWatchNumber == watchNumber)
                    update(
                        state!!.copy(watchError = message(UiR.string.workspace_file_watch_failed)),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && directoryWatchNumber == watchNumber)
                    update(
                        state!!.copy(watchError = message(UiR.string.workspace_file_watch_failed)),
                    )
            }
        }
    }

    private fun watchPreview() {
        val view = state ?: return
        val preview = view.preview ?: return
        val client = owner ?: return
        val own = epoch
        val path = preview.path
        fileWatch = scope.launch {
            try {
                client.changes(view.sessionId, path).collect { change ->
                    if (!current(client, own) || state?.preview?.path != path) return@collect
                    val live = state!!.preview!!
                    when (change) {
                        WorkspaceFileChange.Ready -> {
                            update(state!!.copy(preview = live.copy(watchError = null)))
                            if (
                                live.loading ||
                                    live.kind != WorkspacePreviewKind.Text &&
                                        live.cachePath == null ||
                                    live.kind == WorkspacePreviewKind.Text && live.page == null
                            )
                                verifyAfterRead = true
                            else if (!live.stale) {
                                if (live.kind != WorkspacePreviewKind.Text)
                                    loadBinaryPreview(verification = true)
                                else
                                    loadPage(
                                        live.page!!.offset,
                                        reload = false,
                                        verification = true,
                                    )
                            }
                        }
                        is WorkspaceFileChange.Changed -> {
                            val loadedVersion =
                                if (live.kind != WorkspacePreviewKind.Text) live.fileVersion
                                else live.page?.version
                            if (
                                !change.absent &&
                                    loadedVersion != null &&
                                    change.version == loadedVersion
                            )
                                return@collect
                            previewNumber++
                            previewRead?.cancel()
                            verifyAfterRead = false
                            update(
                                state!!.copy(
                                    preview =
                                        live.copy(
                                            loading = false,
                                            stale = true,
                                            error =
                                                message(
                                                    if (change.absent)
                                                        UiR.string.workspace_file_removed
                                                    else UiR.string.workspace_file_changed,
                                                ),
                                        ),
                                ),
                            )
                        }
                    }
                }
                watchFailed(client, own, path)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                watchFailed(client, own, path)
            }
        }
    }

    private fun watchFailed(client: WorkspaceFilesRepository, own: Long, path: String) {
        if (current(client, own))
            state
                ?.preview
                ?.takeIf { it.path == path }
                ?.let {
                    update(
                        state!!.copy(
                            preview =
                                it.copy(
                                    watchError = message(UiR.string.workspace_file_watch_failed),
                                ),
                        ),
                    )
                }
    }

    fun closePreview() {
        previewNumber++
        previewRead?.cancel()
        fileWatch?.cancel()
        verifyAfterRead = false
        previewFile?.delete()
        previewFile = null
        state?.let { update(it.copy(preview = null)) }
    }

    fun dismissSession(id: String) {
        if (state?.sessionId == id) dismiss()
    }

    fun dismiss() {
        epoch++
        readNumber++
        previewNumber++
        read?.cancel()
        read = null
        previewRead?.cancel()
        directoryWatch?.cancel()
        fileWatch?.cancel()
        directoryRefresh?.cancel()
        directoryPending = false
        verifyAfterRead = false
        searchNumber++
        searchRead?.cancel()
        previewFile?.delete()
        previewFile = null
        update(null)
    }

    private fun current(client: WorkspaceFilesRepository, own: Long) =
        owner === client &&
            own == epoch &&
            generation != null &&
            client.generation == generation &&
            state != null

    private fun update(value: WorkspaceFilesUiState?) {
        state = value
        publish(value)
    }

    override fun onCleared() {
        close()
    }

    fun close() {
        dismiss()
        owner = null
        generation = null
    }
}
