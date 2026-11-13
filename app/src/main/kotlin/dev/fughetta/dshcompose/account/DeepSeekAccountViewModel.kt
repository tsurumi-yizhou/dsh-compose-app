package dev.fughetta.dshcompose.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.account.AccountRepository
import dev.fughetta.dshcompose.domain.account.AccountSignInPhase
import dev.fughetta.dshcompose.domain.account.DeepSeekAccountView
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.ui.account.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * PKCE, callbacks and grants remain in the local engine. This controller holds display state only.
 */
class DeepSeekAccountViewModel(parent: CoroutineScope? = null) : ViewModel() {
    private val scope =
        CoroutineScope(
            (parent ?: viewModelScope).coroutineContext +
                SupervisorJob((parent ?: viewModelScope).coroutineContext[Job]),
        )
    private val mutable = MutableStateFlow(DeepSeekAccountState())
    val state = mutable.asStateFlow()
    private val events = MutableSharedFlow<DeepSeekAccountEffect>(extraBufferCapacity = 8)
    val effects = events.asSharedFlow()
    private val ready = MutableStateFlow<AccountRepository?>(null)
    private var api: AccountRepository? = null
    private var mux: Any? = null
    private var connection = 0L
    private var observing: Job? = null
    private var reading: Job? = null
    private var signingIn: Job? = null
    private var operation: Job? = null
    private var expiry: Job? = null
    private var returnHint: Job? = null
    private var run = 0L
    private var requested = false
    private var opened: String? = null

    fun identityFor(generation: Any?): Boolean =
        api != null && mux != null && generation == mux && state.value.identityConfirmed

    fun connect(repository: AccountRepository?) {
        val next = repository?.generation
        if (api?.generation == next && mux == next && api === repository) return
        connection++
        observing?.cancel()
        reading?.cancel()
        expiry?.cancel()
        returnHint?.cancel()
        val previous = api
        api = repository
        mux = next
        ready.value = api
        mutable.update {
            it.copy(
                connected = api != null,
                identityConfirmed = false,
                attempt = null,
                browserReturned = false,
                phase =
                    if (requested && previous == null) DeepSeekAccountPhase.SigningIn
                    else if (it.phase == DeepSeekAccountPhase.SignedIn)
                        DeepSeekAccountPhase.SignedIn
                    else DeepSeekAccountPhase.Unknown,
            )
        }
        if (previous != null && requested) {
            requested = false
            run++
            signingIn?.cancel()
            mutable.update { it.copy(error = "account/interrupted", busy = false) }
        }
        val owner = api ?: return
        val epoch = connection
        observing = scope.launch {
            owner
                .updates(
                    { current(owner, epoch) },
                    { error -> mutable.update { it.copy(error = code(error)) } },
                )
                .collect { accept(it, owner, epoch) }
        }
    }

    private fun current(owner: AccountRepository, epoch: Long) =
        api === owner && connection == epoch && owner.generation == mux

    fun action(action: DeepSeekAccountAction) {
        when (action) {
            DeepSeekAccountAction.SignIn -> signIn()
            DeepSeekAccountAction.Cancel -> cancel()
            DeepSeekAccountAction.ReopenBrowser -> state.value.attempt?.authorizeUrl?.let(::browser)
            DeepSeekAccountAction.Refresh -> refresh()
            DeepSeekAccountAction.RequestSignOut -> requestSignOut()
            DeepSeekAccountAction.ConfirmSignOut -> signOut()
            DeepSeekAccountAction.DismissSignOut ->
                mutable.update { it.copy(confirmSignOut = false) }
            DeepSeekAccountAction.Usage -> state.value.usageUrl?.let(::browser)
            DeepSeekAccountAction.TopUp -> state.value.topUpUrl?.let(::browser)
            DeepSeekAccountAction.ApiKeys ->
                scope.launch { events.emit(DeepSeekAccountEffect.ApiKeys) }
            is DeepSeekAccountAction.BrowserOpened ->
                if (!action.success)
                    mutable.update { it.copy(error = "account/browser-unavailable") }
        }
    }

    private fun signIn() {
        if (state.value.phase == DeepSeekAccountPhase.SignedIn && state.value.identityConfirmed)
            return
        if (requested) {
            state.value.attempt?.authorizeUrl?.let(::browser)
            return
        }
        requested = true
        opened = null
        val number = ++run
        mutable.update {
            it.copy(
                phase = DeepSeekAccountPhase.SigningIn,
                busy = true,
                error = null,
                profile = null,
                balance = null,
                identityConfirmed = false,
            )
        }
        signingIn = scope.launch {
            try {
                if (api == null) events.emit(DeepSeekAccountEffect.StartLocalEngine)
                val owner = withTimeout(90_000) { ready.filterNotNull().first() }
                val epoch = connection
                val view =
                    owner.beginSignIn(
                        accepted = { requested && number == run && current(owner, epoch) },
                        canCancel = { owner.generation == mux },
                    )
                if (view != null) accept(view, owner, epoch)
            } catch (_: TimeoutCancellationException) {
                if (run == number) fail("account/engine-timeout")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (run == number) fail(code(error))
            }
        }
    }

    private fun cancel() {
        requested = false
        run++
        signingIn?.cancel()
        expiry?.cancel()
        opened = null
        val owner = api
        val epoch = connection
        val id = state.value.attempt?.id
        if (owner == null || id == null) {
            mutable.update {
                it.copy(phase = DeepSeekAccountPhase.SignedOut, attempt = null, busy = false)
            }
            return
        }
        operation?.cancel()
        operation = scope.launch {
            try {
                val view = owner.cancel(id)
                if (current(owner, epoch)) accept(view, owner, epoch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, epoch))
                    mutable.update { it.copy(error = code(error), busy = false) }
            }
        }
    }

    private suspend fun accept(view: DeepSeekAccountView, owner: AccountRepository, epoch: Long) {
        if (!current(owner, epoch)) return
        val attempt = view.attempt
        val phase =
            when {
                view.signedIn -> DeepSeekAccountPhase.SignedIn
                attempt?.phase?.active == true -> DeepSeekAccountPhase.SigningIn
                else -> DeepSeekAccountPhase.SignedOut
            }
        val changed = state.value.phase != phase || !state.value.identityConfirmed
        mutable.update {
            it.copy(
                phase = phase,
                attempt = attempt,
                usageUrl = view.usageUrl,
                topUpUrl = view.topUpUrl,
                busy = false,
                identityConfirmed = !view.signedIn,
                error =
                    when (attempt?.phase) {
                        AccountSignInPhase.Failed,
                        AccountSignInPhase.Expired -> "account/${attempt.error ?: "expired"}"
                        else -> null
                    },
                profile = if (view.signedIn) it.profile else null,
                balance = if (view.signedIn) it.balance else null,
            )
        }
        if (view.signedIn) {
            val wasRequested = requested
            requested = false
            expiry?.cancel()
            refresh()
            if (changed) events.emit(DeepSeekAccountEffect.IdentityChanged)
            if (wasRequested) {
                try {
                    owner.initializeDefaultModel()
                    if (current(owner, epoch)) events.emit(DeepSeekAccountEffect.IdentityChanged)
                } catch (_: Exception) {
                    if (current(owner, epoch))
                        mutable.update { it.copy(error = "account/default-model-failed") }
                }
            }
        } else if (requested && attempt != null) {
            val authorizeUrl = attempt.authorizeUrl
            if (
                attempt.phase == AccountSignInPhase.WaitingBrowser &&
                    authorizeUrl != null &&
                    opened != authorizeUrl
            ) {
                opened = authorizeUrl
                browser(authorizeUrl)
            }
            if (!attempt.phase.active) {
                requested = false
                expiry?.cancel()
            } else {
                expiry?.cancel()
                val delayMs =
                    ((attempt.expiresAt ?: (System.currentTimeMillis() + 11 * 60_000L)) -
                            System.currentTimeMillis())
                        .coerceAtLeast(0)
                expiry = scope.launch {
                    delay(delayMs)
                    if (current(owner, epoch) && state.value.attempt?.id == attempt.id) {
                        cancel()
                        mutable.update { it.copy(error = "account/expired") }
                    }
                }
            }
        }
        if (!view.signedIn && changed) events.emit(DeepSeekAccountEffect.IdentityChanged)
    }

    fun foreground(value: Boolean) {
        returnHint?.cancel()
        if (!value) return
        val owner = api ?: return
        val epoch = connection
        returnHint = scope.launch {
            do {
                try {
                    accept(owner.state(), owner, epoch)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (current(owner, epoch)) mutable.update { it.copy(error = code(error)) }
                }
                if (!current(owner, epoch) || state.value.phase != DeepSeekAccountPhase.SigningIn)
                    break
                if (
                    opened != null &&
                        state.value.attempt?.phase == AccountSignInPhase.WaitingBrowser
                )
                    mutable.update { it.copy(browserReturned = true) }
                delay(2_000)
            } while (current(owner, epoch))
        }
    }

    fun refresh() {
        val owner = api ?: return
        val epoch = connection
        if (state.value.phase != DeepSeekAccountPhase.SignedIn) return
        reading?.cancel()
        reading = scope.launch {
            supervisorScope {
                launch {
                    try {
                        val profile = owner.profile()
                        if (
                            current(owner, epoch) &&
                                state.value.phase == DeepSeekAccountPhase.SignedIn
                        ) {
                            val changed =
                                state.value.profile?.id != profile?.id ||
                                    !state.value.identityConfirmed
                            mutable.update {
                                it.copy(
                                    profile = profile,
                                    profileError = profile == null,
                                    identityConfirmed = !profile?.id.isNullOrBlank(),
                                )
                            }
                            if (changed) events.emit(DeepSeekAccountEffect.IdentityChanged)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (current(owner, epoch))
                            mutable.update {
                                it.copy(profileError = true, identityConfirmed = false)
                            }
                    }
                }
                launch {
                    try {
                        val balance = owner.balance()
                        if (
                            current(owner, epoch) &&
                                state.value.phase == DeepSeekAccountPhase.SignedIn
                        )
                            mutable.update {
                                it.copy(balance = balance, balanceError = balance == null)
                            }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (current(owner, epoch)) mutable.update { it.copy(balanceError = true) }
                    }
                }
            }
        }
    }

    private fun requestSignOut() {
        val owner = api ?: return
        val epoch = connection
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        operation = scope.launch {
            val running =
                try {
                    owner.runningTasks()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            if (current(owner, epoch))
                mutable.update {
                    it.copy(busy = false, confirmSignOut = true, runningAccountTasks = running)
                }
        }
    }

    private fun signOut() {
        val owner = api ?: return
        val epoch = connection
        if (state.value.busy || !state.value.confirmSignOut) return
        mutable.update { it.copy(busy = true) }
        operation = scope.launch {
            try {
                val view = owner.signOut()
                if (current(owner, epoch)) {
                    accept(view, owner, epoch)
                    mutable.update { it.copy(confirmSignOut = false) }
                    events.emit(DeepSeekAccountEffect.IdentityChanged)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, epoch))
                    mutable.update { it.copy(error = code(error), busy = false) }
            }
        }
    }

    private fun browser(url: String) {
        val owner = api ?: return
        val epoch = connection
        val generation = mux
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            mutable.update { it.copy(error = "account/invalid-link") }
            return
        }
        scope.launch {
            if (current(owner, epoch)) events.emit(DeepSeekAccountEffect.Browser(url, generation))
        }
    }

    private fun fail(code: String) {
        requested = false
        mutable.update {
            it.copy(phase = DeepSeekAccountPhase.SignedOut, busy = false, error = code)
        }
    }

    private fun code(error: Exception): String =
        operationErrorCode(error) ?: "account/engine-unavailable"

    override fun onCleared() {
        close()
    }

    fun close() {
        connection++
        requested = false
        run++
        scope.cancel()
        ready.value = null
        api = null
    }
}
