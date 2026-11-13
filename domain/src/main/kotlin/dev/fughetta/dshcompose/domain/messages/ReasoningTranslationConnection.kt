package dev.fughetta.dshcompose.domain.messages

/** Translation requests owned by one connection generation. */
class ReasoningTranslation(
    val generation: dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration,
    val limits: suspend () -> ReasoningTranslationLimits,
    val translate: suspend (String, String, ReasoningTranslationLimits, String) -> String,
)

class ReasoningTranslationException(val code: String, cause: Throwable? = null) :
    Exception(code, cause)
