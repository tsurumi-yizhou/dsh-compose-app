package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.account.AccountSignInAttempt
import dev.fughetta.dshcompose.domain.account.AccountSignInPhase
import dev.fughetta.dshcompose.domain.account.DeepSeekAccountView
import kotlinx.serialization.json.*

fun parseAccountView(value: JsonElement): DeepSeekAccountView {
    val raw = value.objectValue()
    val signedIn =
        when (raw.string("status")) {
            "credential-stored" -> true
            "signed-out" -> false
            else -> throw HostProtocolException("Invalid account state")
        }
    val links = raw["links"] as? JsonObject
    val attempt =
        (raw["attempt"] as? JsonObject)?.let {
            val id = it.string("id") ?: return@let null
            val phase =
                AccountSignInPhase.entries.find { phase -> phase.wire == it.string("phase") }
                    ?: return@let null
            AccountSignInAttempt(
                id,
                phase,
                it.string("authorizeUrl"),
                it.long("expiresAt"),
                it.string("errorCode"),
            )
        }
    return DeepSeekAccountView(
        signedIn,
        links?.string("usageUrl"),
        links?.string("topUpUrl"),
        attempt,
    )
}
