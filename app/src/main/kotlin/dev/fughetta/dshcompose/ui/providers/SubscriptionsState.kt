package dev.fughetta.dshcompose.ui.providers

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*

data class SubscriptionLoginState(
    val subscription: Subscription,
    val nonce: Long,
    val step: LoginStep = LoginStep(LoginPhase.Starting),
    val busy: Boolean = false,
    val error: String? = null,
    val browserOpened: Boolean = false,
)

data class SubscriptionsUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val writing: Boolean = false,
    val statuses: PersistentList<SubscriptionStatus> = persistentListOf(),
    val login: SubscriptionLoginState? = null,
    val signOut: Subscription? = null,
    val error: String? = null,
)
