package dev.fughetta.dshcompose.domain.providers

enum class Subscription(val label: String) {
    ChatGpt("ChatGPT"),
    Claude("Claude"),
}

data class SubscriptionStatus(
    val subscription: Subscription,
    val signedIn: Boolean,
    val account: String?,
)

enum class LoginPhase {
    Starting,
    Open,
    Device,
    Prompt,
    Done,
    Failed,
    Cancelled;

    val finished
        get() = this in setOf(Done, Failed, Cancelled)
}

/**
 * URLs and prompt messages may contain authorization material: never include them in diagnostics.
 */
class LoginStep(
    val phase: LoginPhase,
    val url: String? = null,
    val userCode: String? = null,
    val message: String? = null,
    val placeholder: String? = null,
) {
    override fun toString() = "LoginStep(phase=$phase)"
}
