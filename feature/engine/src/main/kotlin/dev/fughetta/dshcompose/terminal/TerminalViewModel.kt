package dev.fughetta.dshcompose.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.terminal.*
import dev.fughetta.dshcompose.domain.terminal.TerminalEnvironment
import dev.fughetta.dshcompose.domain.terminal.TerminalFrame
import dev.fughetta.dshcompose.ui.terminal.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.connectbot.terminal.TerminalEmulatorFactory

class TerminalViewModel(parent: CoroutineScope? = null) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private val mutable = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = mutable.asStateFlow()
    private val effectsChannel = kotlinx.coroutines.channels.Channel<TerminalEffect>(8)
    val effects = effectsChannel.receiveAsFlow()
    private var owner: TerminalRepository? = null
    private var epoch = 0L
    private var attachmentEpoch = 0L
    private var observing = false
    private var attachment: TerminalSessionHandle? = null
    private var frames: Job? = null
    private var listing: Job? = null
    private var generation: Job? = null
    private var mutation: Job? = null
    private var resize: Job? = null
    private var measured: Pair<Int, Int>? = null

    fun connect(client: TerminalRepository?, hostKey: String?, sessionId: String?) {
        if (
            owner === client && state.value.hostKey == hostKey && state.value.sessionId == sessionId
        )
            return
        epoch++
        teardown()
        owner = client
        measured = null
        mutable.value =
            TerminalState(
                connected = client != null,
                supported = client?.supports == true,
                hostKey = hostKey,
                sessionId = sessionId,
            )
        if (observing) start()
    }

    fun action(action: TerminalAction) {
        when (action) {
            is TerminalAction.Observe -> {
                observing = action.value
                if (observing) start()
                else {
                    epoch++
                    teardown()
                    change {
                        it.copy(
                            tabs =
                                it.tabs
                                    .map { row ->
                                        row.copy(
                                            emulator = null,
                                            writable = false,
                                            phase =
                                                if (row.info?.running == true)
                                                    TerminalPhase.Disconnected
                                                else row.phase,
                                        )
                                    }
                                    .toPersistentList(),
                        )
                    }
                }
            }
            TerminalAction.Refresh -> load()
            is TerminalAction.Select ->
                if (state.value.tabs.any { it.id == action.id }) {
                    detach()
                    change { it.copy(selectedId = action.id) }
                    attach(action.id)
                }
            is TerminalAction.New -> create(action.replacing)
            is TerminalAction.ChooseShell -> change { it.copy(shellPath = action.path) }
            is TerminalAction.Reconnect ->
                if (
                    state.value.tabs.firstOrNull { it.id == action.id }?.phase ==
                        TerminalPhase.Failed
                )
                    create(existing = action.id)
                else attach(action.id)
            is TerminalAction.RequestClose -> change { it.copy(confirmClose = action.id) }
            TerminalAction.ConfirmClose -> closeTab()
            TerminalAction.Dismiss -> change { it.copy(confirmClose = null, renameId = null) }
            is TerminalAction.RequestRename ->
                state.value.tabs
                    .firstOrNull { it.id == action.id }
                    ?.let { row -> change { it.copy(renameId = row.id, renameText = row.title) } }
            is TerminalAction.RenameText -> change { it.copy(renameText = action.text) }
            TerminalAction.SaveRename -> rename()
            is TerminalAction.Size -> {
                measured = action.cols to action.rows
                resize?.cancel()
                resize = scope.launch {
                    delay(150)
                    attachment?.resize(action.cols, action.rows)
                }
            }
            TerminalAction.ConsumeModifiers ->
                change {
                    it.copy(
                        ctrl = if (it.ctrl == 1) 0 else it.ctrl,
                        alt = if (it.alt == 1) 0 else it.alt,
                    )
                }
            is TerminalAction.SpecialKey ->
                if (attachment?.writable == true) {
                    val current = state.value
                    current.selected
                        ?.emulator
                        ?.dispatchKey(
                            (if (current.ctrl != 0) 4 else 0) or (if (current.alt != 0) 2 else 0),
                            action.key,
                        )
                    this.action(TerminalAction.ConsumeModifiers)
                }
            is TerminalAction.Key -> send(action.value)
            is TerminalAction.ModifierKey ->
                change {
                    val old = if (action.ctrl) it.ctrl else it.alt
                    val next = if (action.locked) 2 else if (old == 0) 1 else 0
                    if (action.ctrl) it.copy(ctrl = next) else it.copy(alt = next)
                }
            is TerminalAction.Paste ->
                if (attachment?.writable == true) {
                    if (action.text.length > (state.value.environment?.maxBufferedBytes ?: 2097152))
                        change { it.copy(error = "terminal/input-full") }
                    else state.value.selected?.emulator?.pasteText(action.text)
                }
            TerminalAction.AllowClipboard ->
                state.value.pendingClipboard?.let { text ->
                    change { it.copy(pendingClipboard = null) }
                    effectsChannel.trySend(TerminalEffect.CopyClipboard(text))
                }
            TerminalAction.DenyClipboard -> change { it.copy(pendingClipboard = null) }
        }
    }

    private fun start() {
        val client = owner ?: return
        if (!client.supports || state.value.sessionId == null) return
        generation?.cancel()
        val own = epoch
        generation = scope.launch {
            var previous: Long? = null
            client.link.collect { link ->
                if (epoch != own || owner !== client) return@collect
                val next = link.generation
                if (link.connected && next != previous) {
                    previous = next
                    if (!state.value.loading) load()
                } else if (!link.connected) {
                    detach()
                    change {
                        it.copy(
                            tabs =
                                it.tabs
                                    .map { row ->
                                        if (row.info?.running == true)
                                            row.copy(
                                                phase = TerminalPhase.Disconnected,
                                                writable = false,
                                                emulator = null,
                                            )
                                        else row
                                    }
                                    .toPersistentList(),
                        )
                    }
                }
            }
        }
        load()
    }

    private fun load() {
        val client = owner ?: return
        val session = state.value.sessionId ?: return
        if (!observing || !client.supports || state.value.busy) return
        val own = epoch
        listing?.cancel()
        change { it.copy(loading = true, error = null) }
        listing = scope.launch {
            try {
                val (environment, shells, rows) = client.inventory(session)
                if (!current(client, own)) return@launch
                detach()
                val keep = rows.map { it.id }.toSet()
                client.keepRetained(scope, keep)
                change {
                    it.copy(
                        loading = false,
                        environment = environment,
                        shells = shells,
                        tabs =
                            rows
                                .map { row ->
                                    TerminalTab(
                                        row.id,
                                        row.title,
                                        if (row.running) TerminalPhase.Connecting
                                        else TerminalPhase.Ended,
                                        row,
                                    )
                                }
                                .toPersistentList(),
                        selectedId =
                            it.selectedId?.takeIf(keep::contains)
                                ?: rows.firstOrNull { row -> row.running }?.id
                                ?: rows.firstOrNull()?.id,
                    )
                }
                rows.filter { it.running }.forEach { hold(client, session, it.id, own) }
                if (rows.isEmpty()) create() else state.value.selectedId?.let(::attach)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own)) change { it.copy(loading = false, error = code(error)) }
            }
        }
    }

    private fun hold(client: TerminalRepository, session: String, id: String, own: Long) {
        client.retain(scope, session, id, { current(client, own) }) { error ->
            tab(id) {
                it.copy(
                    phase =
                        if (operationErrorCode(error) == "terminal/unavailable")
                            TerminalPhase.Missing
                        else TerminalPhase.Disconnected,
                    writable = false,
                )
            }
        }
    }

    private fun attach(id: String) {
        val client = owner ?: return
        val session = state.value.sessionId ?: return
        if (!observing || !client.supports || state.value.selectedId != id) return
        val row = state.value.tabs.firstOrNull { it.id == id } ?: return
        detach()
        val own = epoch
        val token = ++attachmentEpoch
        tab(id) { it.copy(phase = TerminalPhase.Connecting, writable = false) }
        hold(client, session, id, own)
        frames = scope.launch {
            try {
                client.awaitRetained(scope, id)
                if (!current(client, own) || attachmentEpoch != token) return@launch
                val link =
                    client.attach(
                        scope,
                        session,
                        id,
                        state.value.environment ?: TerminalEnvironment(),
                    )
                attachment = link
                link.frames.collect { frame ->
                    if (!current(client, own) || attachmentEpoch != token) return@collect
                    when (frame) {
                        is TerminalFrame.Snapshot -> {
                            val emulator =
                                TerminalEmulatorFactory.create(
                                    initialRows = frame.info.rows.coerceIn(1, 200),
                                    initialCols = frame.info.cols.coerceIn(2, 500),
                                    onKeyboardInput = { bytes ->
                                        if (current(client, own) && attachmentEpoch == token)
                                            attachment?.write(bytes.toString(Charsets.UTF_8))
                                    },
                                    onResize = { size ->
                                        action(TerminalAction.Size(size.columns, size.rows))
                                    },
                                    onClipboardCopy = { text ->
                                        if (
                                            current(client, own) &&
                                                attachmentEpoch == token &&
                                                state.value.pendingClipboard == null
                                        )
                                            change { it.copy(pendingClipboard = text.take(65536)) }
                                    },
                                )
                            try {
                                withContext(Dispatchers.Default) {
                                    emulator.writeInput(frame.screen.toByteArray(Charsets.UTF_8))
                                }
                            } catch (error: CancellationException) {
                                emulator.close()
                                throw error
                            }
                            if (!current(client, own) || attachmentEpoch != token) {
                                emulator.close()
                                return@collect
                            }
                            tab(id) {
                                it.copy(
                                    info = frame.info,
                                    title = frame.info.title,
                                    phase =
                                        if (frame.info.running) TerminalPhase.Live
                                        else TerminalPhase.Ended,
                                    writable = link.writable,
                                    emulator = emulator,
                                    error = null,
                                )
                            }
                            measured?.let { link.resize(it.first, it.second) }
                        }
                        is TerminalFrame.Output ->
                            state.value.selected?.emulator?.let { emulator ->
                                withContext(Dispatchers.Default) {
                                    emulator.writeInput(frame.data.toByteArray(Charsets.UTF_8))
                                }
                            }
                        is TerminalFrame.Status ->
                            tab(id) {
                                it.copy(
                                    info = frame.info,
                                    title = frame.info.title,
                                    phase =
                                        if (frame.info.running) TerminalPhase.Live
                                        else TerminalPhase.Ended,
                                    writable = link.writable,
                                )
                            }
                        is TerminalFrame.Ack ->
                            if (state.value.selected?.writable != link.writable)
                                tab(id) { it.copy(writable = link.writable) }
                    }
                }
                if (current(client, own) && attachmentEpoch == token)
                    tab(id) { it.copy(phase = TerminalPhase.Ended, writable = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own) && attachmentEpoch == token)
                    tab(id) {
                        it.copy(
                            phase =
                                if (operationErrorCode(error) == "terminal/unavailable")
                                    TerminalPhase.Missing
                                else TerminalPhase.Disconnected,
                            writable = false,
                            error = code(error),
                        )
                    }
            }
        }
    }

    private fun create(replacing: String? = null, existing: String? = null) {
        val client = owner ?: return
        val session = state.value.sessionId ?: return
        if (!observing || state.value.busy || !client.supports) return
        val id = existing ?: java.util.UUID.randomUUID().toString()
        val own = epoch
        val env = state.value.environment ?: TerminalEnvironment()
        change {
            it.copy(
                busy = true,
                error = null,
                selectedId = id,
                tabs =
                    if (existing == null)
                        it.tabs.adding(TerminalTab(id, phase = TerminalPhase.Starting))
                    else it.tabs,
            )
        }
        mutation = scope.launch {
            try {
                if (measured == null) delay(400)
                val size = measured ?: (80 to 24)
                val info =
                    client.create(
                        session,
                        id,
                        size.first.coerceIn(2, maxOf(2, env.maxCols)),
                        size.second.coerceIn(1, maxOf(1, env.maxRows)),
                        state.value.shellPath,
                    )
                if (!current(client, own)) return@launch
                tab(id) { it.copy(info = info, title = info.title) }
                attach(id)
                if (replacing != null) {
                    client.close(session, replacing)
                    if (current(client, own)) {
                        client.releaseRetained(scope, replacing)
                        change {
                            it.copy(
                                tabs =
                                    it.tabs
                                        .filterNot { row -> row.id == replacing }
                                        .toPersistentList(),
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own))
                    tab(id) { it.copy(phase = TerminalPhase.Failed, error = code(error)) }
            } finally {
                if (current(client, own)) change { it.copy(busy = false) }
            }
        }
    }

    private fun closeTab() {
        val client = owner ?: return
        val session = state.value.sessionId ?: return
        val id = state.value.confirmClose ?: return
        if (state.value.busy) return
        val own = epoch
        change { it.copy(busy = true, confirmClose = null) }
        mutation = scope.launch {
            try {
                client.close(session, id)
                if (current(client, own)) {
                    if (state.value.selectedId == id) detach()
                    client.releaseRetained(scope, id)
                    change { current ->
                        val tabs = current.tabs.filterNot { it.id == id }.toPersistentList()
                        current.copy(
                            tabs = tabs,
                            selectedId =
                                current.selectedId?.takeUnless { it == id }
                                    ?: tabs.firstOrNull()?.id,
                        )
                    }
                    state.value.selectedId?.let(::attach)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own)) change { it.copy(error = code(error)) }
            } finally {
                if (current(client, own)) change { it.copy(busy = false) }
            }
        }
    }

    private fun rename() {
        val client = owner ?: return
        val session = state.value.sessionId ?: return
        val id = state.value.renameId ?: return
        val title =
            state.value.renameText.trim().take(120).trimEnd { Character.isHighSurrogate(it) }
        if (title.isBlank() || state.value.busy) return
        val own = epoch
        change { it.copy(busy = true) }
        mutation = scope.launch {
            try {
                client.rename(session, id, title)
                if (current(client, own)) {
                    tab(id) { it.copy(title = title) }
                    change { it.copy(renameId = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own)) change { it.copy(error = code(error)) }
            } finally {
                if (current(client, own)) change { it.copy(busy = false) }
            }
        }
    }

    private fun send(text: String) {
        val current = state.value
        attachment?.write(terminalModifiers(text, current.ctrl != 0, current.alt != 0))
        if (current.ctrl == 1 || current.alt == 1)
            change {
                it.copy(
                    ctrl = if (it.ctrl == 1) 0 else it.ctrl,
                    alt = if (it.alt == 1) 0 else it.alt,
                )
            }
    }

    private fun tab(id: String, transform: (TerminalTab) -> TerminalTab) = change {
        it.copy(
            tabs =
                it.tabs.map { row -> if (row.id == id) transform(row) else row }.toPersistentList(),
        )
    }

    private fun detach() {
        attachmentEpoch++
        frames?.cancel()
        frames = null
        attachment?.close()
        attachment = null
        val old = state.value.selected?.emulator
        if (old != null) {
            tab(state.value.selectedId!!) { it.copy(emulator = null, writable = false) }
            scope.launch(NonCancellable + Dispatchers.Default) { old.close() }
        }
    }

    private fun teardown() {
        listing?.cancel()
        mutation?.cancel()
        generation?.cancel()
        resize?.cancel()
        detach()
        owner?.releaseRetentions(scope)
    }

    private fun current(client: TerminalRepository, own: Long) = owner === client && epoch == own

    private fun change(transform: (TerminalState) -> TerminalState) {
        mutable.value = transform(state.value).copy(revision = state.value.revision + 1)
    }

    private fun code(error: Exception) = operationErrorCode(error) ?: "TERMINAL_CONNECTION_FAILED"

    override fun onCleared() {
        close()
    }

    fun close() {
        epoch++
        teardown()
        owner = null
        effectsChannel.close()
    }
}
