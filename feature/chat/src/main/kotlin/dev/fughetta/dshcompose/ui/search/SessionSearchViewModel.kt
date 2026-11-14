package dev.fughetta.dshcompose.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns search input, IME composition and the visible request; repositories own catalog data. */
class SessionSearchViewModel(private val repository: SessionsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SessionSearchUiState())
    val uiState = mutable.asStateFlow()
    private var owner = 0L
    private var host = ""
    private var epoch = 0L
    private var request = 0L
    private var derivation = 0L
    private var content: Job? = null
    private var local: Job? = null
    private val state
        get() = mutable.value

    private var observing = false
    private var composing = false
    private var committed = ""
    private var contentQuery = ""
    private var hits: List<SessionContentHit> = emptyList()
    private var contentStatus = ContentSearchStatus.Idle
    private var hasMore = false
    private var unavailable = false

    init {
        viewModelScope.launch { repository.context.collect(::connect) }
        viewModelScope.launch { repository.catalogRevision.collect { derive() } }
    }

    private fun connect(context: SearchContext) {
        val client = context.connection
        val hostId = context.host
        if (owner == client && host == hostId) return
        val hostChanged = host != hostId
        host = hostId
        request++
        epoch++
        content?.cancel()
        local?.cancel()
        content = null
        local = null
        derivation++
        owner = client
        unavailable = false
        hits = emptyList()
        contentQuery = ""
        hasMore = false
        contentStatus = ContentSearchStatus.Idle
        if (hostChanged) {
            composing = false
            committed = ""
        }
        update(
            SessionSearchUiState(
                epoch = epoch,
                input = state.input.takeUnless { hostChanged }.orEmpty(),
            ),
        )
        if (observing) {
            if (!composing) schedule()
            derive()
        }
    }

    fun observe(value: Boolean) {
        if (observing == value) return
        observing = value
        if (value) {
            schedule()
            derive()
        } else {
            request++
            derivation++
            content?.cancel()
            local?.cancel()
            content = null
            local = null
            if (!composing) committed = state.input
            composing = false
            hits = emptyList()
            contentQuery = ""
            hasMore = false
            contentStatus = ContentSearchStatus.Idle
            update(
                SessionSearchUiState(epoch = ++epoch, input = committed, query = committed.trim()),
            )
        }
    }

    fun dismiss() {
        observe(false)
        committed = ""
        composing = false
        update(SessionSearchUiState(epoch = ++epoch))
    }

    fun input(text: String, composingStart: Int?, composingEnd: Int?) {
        if (!observing) return
        val limited = limitSessionSearchInput(text)
        val inComposition =
            composingStart != null &&
                composingEnd != null &&
                composingStart >= 0 &&
                composingEnd > composingStart &&
                composingEnd <= limited.length
        if (
            limited == state.input &&
                inComposition == composing &&
                (!inComposition || committed == limited.removeRange(composingStart, composingEnd))
        )
            return
        composing = inComposition
        committed =
            if (inComposition) limited.removeRange(composingStart, composingEnd) else limited
        update(state.copy(input = limited))
        if (!inComposition) schedule()
        derive()
    }

    fun retry() {
        if (contentStatus == ContentSearchStatus.Failed && !composing && observing) {
            schedule(force = true)
            derive()
        }
    }

    private fun current(number: Long) = observing && repository.searchActive && request == number

    private fun schedule(force: Boolean = false) {
        val query = state.input.trim()
        if (
            !force &&
                sessionSearchKey(query) == sessionSearchKey(contentQuery) &&
                contentStatus != ContentSearchStatus.Idle
        )
            return
        val number = ++request
        content?.cancel()
        contentQuery = query
        hits = emptyList()
        hasMore = false
        if (query.isEmpty() || owner == 0L || !repository.searchActive) {
            contentStatus = ContentSearchStatus.Idle
            return
        }
        if (unavailable) {
            contentStatus = ContentSearchStatus.Unavailable
            return
        }
        contentStatus = ContentSearchStatus.Pending
        content = viewModelScope.launch {
            try {
                if (!force) delay(250)
                val page = repository.search(query)
                if (!current(number)) return@launch
                hits = page.items
                hasMore = page.hasMore
                contentStatus = ContentSearchStatus.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!current(number)) return@launch
                unavailable = (error as? SessionSearchException)?.unavailable == true
                contentStatus =
                    if (unavailable) ContentSearchStatus.Unavailable else ContentSearchStatus.Failed
            }
            derive()
        }
    }

    private fun derive() {
        if (!observing) return
        val number = ++derivation
        val own = epoch
        local?.cancel()
        val version = repository.catalogRevision.value
        val input = state.input.trim()
        val fallback = committed.trim().takeIf { composing }
        val savedHits = hits
        val key = sessionSearchKey(contentQuery)
        val status = contentStatus
        val composingNow = composing
        val more = hasMore
        update(state.copy(deriving = true))
        local = viewModelScope.launch {
            suspend fun results(query: String) =
                repository.results(
                    query,
                    savedHits.takeIf { sessionSearchKey(query) == key }.orEmpty(),
                )
            val full = results(input)
            val query = if (fallback != null && full.isEmpty) fallback else input
            val computed = query to if (query == input) full else results(query)
            if (
                number != derivation ||
                    own != epoch ||
                    !observing ||
                    version != repository.catalogRevision.value
            )
                return@launch
            val same = sessionSearchKey(computed.first) == key
            update(
                state.copy(
                    revision = state.revision + 1,
                    query = computed.first,
                    results = computed.second,
                    deriving = false,
                    status =
                        when {
                            computed.first.isEmpty() -> ContentSearchStatus.Idle
                            status == ContentSearchStatus.Unavailable -> status
                            same -> status
                            composingNow -> ContentSearchStatus.Idle
                            else -> ContentSearchStatus.Pending
                        },
                    hasMore = same && more,
                ),
            )
        }
    }

    private fun update(value: SessionSearchUiState) {
        mutable.value = value
    }

    fun canOpen(epoch: Long): Boolean =
        epoch == state.epoch &&
            repository.searchActive &&
            repository.context.value.connection == owner &&
            observing

    fun handle(action: AppAction): Boolean {
        when (action) {
            is AppAction.ObserveSessionSearch -> observe(action.observing)
            is AppAction.QuerySessions ->
                input(action.text, action.composingStart, action.composingEnd)
            AppAction.RetrySessionSearch -> retry()
            AppAction.DismissSessionSearch -> dismiss()
            else -> return false
        }
        return true
    }
}
