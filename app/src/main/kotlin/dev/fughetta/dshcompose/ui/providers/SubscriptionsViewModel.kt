package dev.fughetta.dshcompose.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Grants belong to the engine. Only this controller's memory holds an in-progress attempt. */
class SubscriptionsViewModel(private val repository: StateFlow<SubscriptionsRepository?>) :
    ViewModel() {
    private val scope
        get() = viewModelScope

    private val mutable = MutableStateFlow(SubscriptionsUiState())
    val uiState = mutable.asStateFlow()
    private val state
        get() = mutable.value

    private var owner: SubscriptionsRepository? = null
    private var connection = 0L
    private var observing = false
    private var read: Job? = null
    private var readNumber = 0L
    private var operation: Job? = null
    private var polling: Job? = null
    private var attempt: SubscriptionLogin? = null
    private var loginNumber = 0L
    private var closeRequested = false

    init {
        scope.launch {
            repository.collectLatest { operations ->
                connect(operations)
                operations?.changes?.drop(1)?.collect { invalidate() }
            }
        }
    }

    fun handle(action: AppAction): Boolean {
        connect(repository.value)
        when (action) {
            is AppAction.ObserveSubscriptions -> {
                observe(action.observing)
                return true
            }
            AppAction.RefreshSubscriptions -> {
                refresh()
                return true
            }
            is AppAction.BeginSubscriptionLogin -> {
                begin(action.subscription)
                return true
            }
            AppAction.RetrySubscriptionLogin -> {
                dismissLogin(retry = true)
                return true
            }
            is AppAction.AnswerSubscriptionLogin -> {
                answer(action.answer)
                return true
            }
            is AppAction.CloseSubscriptionLogin -> {
                dismissLogin(nonce = action.nonce)
                return true
            }
            is AppAction.SubscriptionBrowserOpened -> {
                browserOpened(action.nonce)
                return true
            }
            is AppAction.RequestSubscriptionSignOut -> {
                requestSignOut(action.subscription)
                return true
            }
            AppAction.ConfirmSubscriptionSignOut -> {
                confirmSignOut()
                return true
            }
            AppAction.DismissSubscriptionSignOut -> {
                dismissSignOut()
                return true
            }
            else -> return false
        }
    }

    private fun connect(client: SubscriptionsRepository?) {
        if (owner === client) return
        retire()
        owner = client
        update(SubscriptionsUiState(connected = client != null))
        if (observing) refresh()
    }

    private fun observe(value: Boolean) {
        observing = value
        if (value) {
            refresh()
            poll()
        } else {
            readNumber++
            read?.cancel()
            polling?.cancel()
            update(state.copy(loading = false))
        }
    }

    private fun invalidate() {
        if (observing) refresh()
    }

    private fun refresh() {
        if (state.writing) return
        val client = owner ?: return
        val own = connection
        if (!current(client, own)) return
        val number = ++readNumber
        read?.cancel()
        read = scope.launch {
            update(state.copy(loading = true, error = null))
            try {
                val statuses = client.statuses()
                if (current(client, own) && number == readNumber)
                    update(state.copy(statuses = statuses))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && number == readNumber)
                    update(state.copy(error = "subscriptions/subscriptions_read_failed"))
            } finally {
                if (current(client, own) && number == readNumber)
                    update(state.copy(loading = false))
            }
        }
    }

    private fun begin(subscription: Subscription) {
        val client = owner ?: return
        if (!state.connected || state.writing || state.login != null || state.signOut != null)
            return
        val own = connection
        if (!current(client, own)) return
        val number = ++loginNumber
        closeRequested = false
        update(
            state.copy(
                login = SubscriptionLoginState(subscription, number, busy = true),
                writing = true,
                error = null,
            ),
        )
        operation = scope.launch {
            try {
                val login = client.begin(subscription)
                if (!current(client, own) || number != loginNumber) {
                    login.close()
                    return@launch
                }
                attempt = login
                if (current(client, own) && number == loginNumber) {
                    update(state.copy(writing = false, login = state.login?.copy(busy = false)))
                    if (closeRequested) dismissLogin() else poll()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && number == loginNumber)
                    update(
                        state.copy(
                            writing = false,
                            login =
                                state.login?.copy(
                                    busy = false,
                                    step = LoginStep(LoginPhase.Failed),
                                    error = "subscriptions/subscription_login_failed",
                                ),
                        ),
                    )
                if (current(client, own) && closeRequested) dismissLogin()
            }
        }
    }

    private fun poll() {
        if (!observing || state.writing || polling?.isActive == true) return
        val client = owner ?: return
        val id = attempt ?: return
        val own = connection
        if (!current(client, own)) return
        val number = loginNumber
        polling = scope.launch {
            while (isActive && current(client, own) && number == loginNumber && attempt == id) {
                try {
                    val step = id.poll()
                    if (!current(client, own) || number != loginNumber || attempt != id)
                        return@launch
                    update(
                        state.copy(
                            login =
                                state.login?.let {
                                    it.copy(
                                        step = step,
                                        error = null,
                                        browserOpened = it.browserOpened && it.step.url == step.url,
                                    )
                                },
                        ),
                    )
                    if (step.phase.finished) {
                        attempt = null
                        if (step.phase == LoginPhase.Done) {
                            refresh()
                        }
                        return@launch
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (current(client, own) && number == loginNumber)
                        update(
                            state.copy(
                                login =
                                    state.login?.copy(
                                        error = "subscriptions/subscription_poll_failed",
                                    ),
                            ),
                        )
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private fun answer(answer: ProviderKey) {
        val client = owner
        val id = attempt
        if (
            client == null ||
                id == null ||
                state.writing ||
                state.login?.step?.phase != LoginPhase.Prompt
        ) {
            answer.close()
            return
        }
        val own = connection
        if (!current(client, own)) {
            answer.close()
            return
        }
        val number = loginNumber
        polling?.cancel()
        polling = null
        update(state.copy(writing = true, login = state.login?.copy(busy = true, error = null)))
        operation =
            scope
                .launch {
                    try {
                        id.answer(answer)
                        if (current(client, own) && number == loginNumber)
                            update(
                                state.copy(
                                    login =
                                        state.login?.copy(step = LoginStep(LoginPhase.Starting)),
                                ),
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (current(client, own) && number == loginNumber)
                            update(
                                state.copy(
                                    login =
                                        state.login?.copy(
                                            error = "subscriptions/subscription_answer_failed",
                                        ),
                                ),
                            )
                    } finally {
                        if (current(client, own) && number == loginNumber) {
                            update(
                                state.copy(
                                    writing = false,
                                    login = state.login?.copy(busy = false),
                                ),
                            )
                            if (closeRequested) dismissLogin() else poll()
                        }
                    }
                }
                .also { it.invokeOnCompletion { answer.close() } }
    }

    private fun browserOpened(nonce: Long) {
        state.login
            ?.takeIf { it.nonce == nonce }
            ?.let { update(state.copy(login = it.copy(browserOpened = true))) }
    }

    private fun dismissLogin(retry: Boolean = false, nonce: Long? = null) {
        val login = state.login ?: return
        if (nonce != null && nonce != login.nonce) return
        val client = owner ?: return
        if (state.writing) {
            if (!retry) closeRequested = true
            return
        }
        val own = connection
        if (!current(client, own)) return
        val number = loginNumber
        val id = attempt
        polling?.cancel()
        polling = null
        update(state.copy(writing = true, login = login.copy(busy = true, error = null)))
        operation = scope.launch {
            try {
                if (id != null) id.cancel()
                if (!current(client, own) || number != loginNumber) return@launch
                attempt = null
                closeRequested = false
                update(state.copy(writing = false, login = null))
                if (retry) begin(login.subscription) else refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && number == loginNumber)
                    update(
                        state.copy(
                            writing = false,
                            login =
                                login.copy(
                                    busy = false,
                                    error = "subscriptions/subscription_cancel_failed",
                                ),
                        ),
                    )
            }
        }
    }

    private fun requestSignOut(subscription: Subscription) {
        if (
            !state.connected ||
                state.writing ||
                state.login != null ||
                state.statuses.none { it.subscription == subscription && it.signedIn }
        )
            return
        update(state.copy(signOut = subscription, error = null))
    }

    private fun dismissSignOut() {
        if (!state.writing) update(state.copy(signOut = null))
    }

    private fun confirmSignOut() {
        val subscription = state.signOut ?: return
        val client = owner ?: return
        if (state.writing) return
        val own = connection
        if (!current(client, own)) return
        readNumber++
        read?.cancel()
        update(state.copy(writing = true, loading = false, error = null))
        operation = scope.launch {
            try {
                client.signOut(subscription)
                if (!current(client, own)) return@launch
                update(state.copy(signOut = null, writing = false))
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own))
                    update(
                        state.copy(
                            writing = false,
                            error = "subscriptions/subscription_sign_out_failed",
                        ),
                    )
            }
        }
    }

    private fun retire() {
        val oldAttempt = attempt
        connection++
        loginNumber++
        readNumber++
        read?.cancel()
        polling?.cancel()
        polling = null
        operation?.cancel()
        attempt = null
        closeRequested = false
        oldAttempt?.close()
    }

    private fun current(client: SubscriptionsRepository, own: Long) =
        owner === client && own == connection && repository.value === client

    private fun update(next: SubscriptionsUiState) {
        mutable.value = next
    }

    override fun onCleared() {
        retire()
        owner = null
    }
}
