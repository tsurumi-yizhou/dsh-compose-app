package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.messages.ReasoningTranslationLimits
import kotlinx.serialization.json.*

suspend fun ProtocolClient.cotTranslationLimits(): ReasoningTranslationLimits {
    val result = connection.call("cotTranslation/limits", JsonObject(emptyMap())).objectValue()
    val prefs = result["preferences"] as? JsonObject ?: JsonObject(emptyMap())
    return ReasoningTranslationLimits(
        (result["maxTextChars"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 4000,
        prefs.string("provider") ?: "bing",
        prefs.string("targetLanguage") ?: "auto",
        (result["availableProviders"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        },
    )
}

suspend fun ProtocolClient.translateReasoning(
    text: String,
    targetLanguage: String,
    provider: String,
    sessionId: String,
): String {
    val result =
        connection.call(
            "cotTranslation/translate",
            buildJsonObject {
                putJsonObject("request") {
                    put("text", text)
                    put("targetLanguage", targetLanguage)
                    put("provider", provider)
                    put("sessionId", sessionId)
                }
            },
        )
    return (result as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw HostProtocolException("cotTranslation/invalid-result")
}
