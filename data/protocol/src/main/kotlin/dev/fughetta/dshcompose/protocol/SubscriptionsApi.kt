package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

internal val Subscription.wire: String
    get() =
        when (this) {
            Subscription.ChatGpt -> "openai-codex"
            Subscription.Claude -> "anthropic"
        }

suspend fun ProtocolClient.subscriptions(): PersistentList<SubscriptionStatus> {
    val value = connection.call("appAuth/list").objectValue()
    return (value["providers"] as? JsonArray)
        .orEmpty()
        .mapNotNull { raw ->
            val row = raw.objectValue()
            val subscription =
                Subscription.entries.firstOrNull { it.wire == row.string("id") }
                    ?: return@mapNotNull null
            SubscriptionStatus(subscription, row.boolean("signedIn") == true, row.string("account"))
        }
        .toPersistentList()
}

suspend fun ProtocolClient.beginLogin(subscription: Subscription): String =
    connection
        .call(
            "appAuth/begin",
            buildJsonObject { put("provider", subscription.wire) },
        )
        .objectValue()
        .requiredString("attemptId")
        .also { if (it.isBlank()) throw HostProtocolException("Missing login attempt") }

suspend fun ProtocolClient.pollLogin(attemptId: String): LoginStep {
    val value =
        connection
            .call("appAuth/poll", buildJsonObject { put("attemptId", attemptId) })
            .objectValue()
    val phase =
        LoginPhase.entries.firstOrNull { it.name.lowercase() == value.string("phase") }
            ?: throw HostProtocolException("Invalid login phase")
    return LoginStep(
        phase,
        value.string("url"),
        value.string("userCode"),
        value.string("message"),
        value.string("placeholder"),
    )
}

suspend fun ProtocolClient.answerLogin(attemptId: String, answer: ProviderKey) {
    connection.call(
        "appAuth/answer",
        buildJsonObject {
            put("attemptId", attemptId)
            put("value", answer.text())
        },
    )
}

suspend fun ProtocolClient.cancelLogin(attemptId: String) {
    connection.call("appAuth/cancel", buildJsonObject { put("attemptId", attemptId) })
}

suspend fun ProtocolClient.signOut(subscription: Subscription) {
    connection.call("appAuth/signOut", buildJsonObject { put("provider", subscription.wire) })
}
