package dev.fughetta.dshcompose.domain.usage

import dev.fughetta.dshcompose.domain.chat.ToolPayload

data class SessionUsage(
    val uncachedInput: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val pressureTokens: Long? = null,
    val projectedTokens: Long? = null,
    val contextWindow: Long? = null,
    val systemTokens: Long? = null,
    val toolsTokens: Long? = null,
    val messageTokens: Long? = null,
    val turns: Long? = null,
    val llmMs: Long? = null,
    val toolMs: Long? = null,
) {
    val input
        get() = uncachedInput + cacheRead + cacheWrite

    val cacheHitRate
        get() = if (input > 0) cacheRead.toDouble() / input else null

    val contextTokens
        get() = projectedTokens ?: pressureTokens

    val contextFraction: Double?
        get() {
            val window = contextWindow ?: return null
            val tokens = contextTokens ?: return null
            return if (window > 0) (tokens.toDouble() / window).coerceIn(0.0, 1.0) else null
        }
}

private fun Map<String, ToolPayload>.obj(key: String): Map<String, ToolPayload> =
    this[key]?.asObject ?: emptyMap()

private fun Map<String, ToolPayload>.long(key: String): Long? = this[key]?.asLong

/** Reads the fields of the host's projection `values` object. */
fun sessionUsageFrom(values: Map<String, ToolPayload>): SessionUsage {
    val tokens = values.obj("tokenUsage")
    val pressure = values.obj("contextPressure")
    val split = values.obj("contextBreakdown")
    val stats = values.obj("sessionStats")
    return SessionUsage(
        tokens.long("uncachedInputTokens") ?: 0,
        tokens.long("outputTokens") ?: 0,
        tokens.long("cacheReadTokens") ?: 0,
        tokens.long("cacheWriteTokens") ?: 0,
        pressure.long("pressureTokens"),
        pressure.long("projectedTokens"),
        pressure.long("contextWindow"),
        split.long("systemTokens"),
        split.long("toolsTokens"),
        split.long("messageTokens"),
        stats.long("turns"),
        stats.long("llmMs"),
        stats.long("toolMs"),
    )
}

fun formatUsageTokens(number: Long): String {
    val sign = if (number < 0) "-" else ""
    val value = number.toBigInteger().abs()
    if (value < java.math.BigInteger.valueOf(1000)) return "$number"
    fun trim(text: String) = text.trimEnd('0').trimEnd('.')
    val n = value.toDouble()
    if (n < 1000000) {
        val k = n / 1000
        val text =
            if (k >= 100) String.format(java.util.Locale.ROOT, "%.0f", k)
            else trim(String.format(java.util.Locale.ROOT, "%.1f", k))
        if (text != "1000") return "$sign${text}K"
    }
    return "$sign${trim(String.format(java.util.Locale.ROOT,"%.2f",n/1000000))}M"
}

fun formatUsagePercent(fraction: Double) =
    if (fraction > 0 && fraction < .01) "<1%" else "${kotlin.math.round(fraction*100).toInt()}%"
