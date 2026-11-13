package dev.fughetta.dshcompose.domain.messages

data class TranscriptNoticeData(
    val code: String? = null,
    val retry: Long? = null,
    val maxRetries: Long? = null,
    val delayMillis: Long? = null,
)

/** null repeatText means send the UI’s localized Continue through its regular prompt pipeline. */
data class TranscriptRetryPlan(val repeatText: String?)
