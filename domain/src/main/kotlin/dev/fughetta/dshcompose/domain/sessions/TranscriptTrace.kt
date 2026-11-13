package dev.fughetta.dshcompose.domain.sessions

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

data class TranscriptTokenUsage(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val requests: Int = 0,
) {
    val total
        get() = input + cacheRead + cacheWrite + output

    operator fun plus(other: TranscriptTokenUsage) =
        TranscriptTokenUsage(
            input + other.input,
            cacheRead + other.cacheRead,
            cacheWrite + other.cacheWrite,
            output + other.output,
            reasoning + other.reasoning,
            requests + other.requests,
        )
}

data class TranscriptSpan(
    val kind: String,
    val seq: Long,
    val step: Long,
    val start: Long,
    val end: Long? = null,
    val label: String? = null,
    val callId: String? = null,
    val arguments: String? = null,
    val failed: Boolean = false,
    val cutShort: Boolean = false,
    val outcome: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val firstToken: Long? = null,
    val lastToken: Long? = null,
    val retries: Int = 0,
    val usage: TranscriptTokenUsage = TranscriptTokenUsage(),
) {
    val duration
        get() = end?.let { (it - start).coerceAtLeast(0) }

    val ttft
        get() = firstToken?.let { (it - start).coerceAtLeast(0) }

    val tokensPerSecond: Double?
        get() {
            val duration =
                firstToken?.let { first -> (end ?: lastToken)?.minus(first) } ?: return null
            return if (duration >= 200 && usage.output > 0) usage.output * 1000.0 / duration
            else null
        }
}

data class TranscriptTurnTrace(
    val id: String,
    val startSeq: Long,
    val start: Long,
    val endSeq: Long? = null,
    val end: Long? = null,
    val reason: String? = null,
    val spans: PersistentList<TranscriptSpan> = persistentListOf(),
    val usage: TranscriptTokenUsage = TranscriptTokenUsage(),
    val steps: Int = 0,
    val artifacts: PersistentList<TranscriptArtifact> = persistentListOf(),
    val changes: PersistentList<TranscriptFileChange> = persistentListOf(),
) {
    val completed
        get() = end != null

    val duration
        get() = end?.let { (it - start).coerceAtLeast(0) }
}
