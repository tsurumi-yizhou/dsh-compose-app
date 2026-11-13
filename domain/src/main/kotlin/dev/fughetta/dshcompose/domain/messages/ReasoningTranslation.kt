package dev.fughetta.dshcompose.domain.messages

data class ReasoningTranslationLimits(
    val maxTextChars: Int,
    val provider: String,
    val targetLanguage: String,
    val availableProviders: List<String>,
) {
    val usable
        get() = availableProviders.isEmpty() || provider in availableProviders

    fun targetFor(language: String) = if (targetLanguage == "auto") language else targetLanguage
}

data class ReasoningFragment(val text: String, val suffix: String, val complete: Boolean)

private val paragraphBreak = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)+")
private val trailingWord = Regex("\\s+\\S*$")

/** UTF-16 request windows preserve paragraph separators and never split a surrogate pair. */
fun reasoningFragments(text: String, running: Boolean, maxTextChars: Int): List<ReasoningFragment> {
    require(maxTextChars >= 2)
    val result = mutableListOf<ReasoningFragment>()
    var start = 0
    fun paragraph(end: Int, suffix: String, complete: Boolean) {
        var offset = start
        while (end - offset > maxTextChars) {
            var cut = offset + maxTextChars
            if (text[cut - 1].isHighSurrogate()) cut--
            val boundary = trailingWord.find(text.substring(offset, cut))
            if (boundary != null && boundary.range.first > 0) cut = offset + boundary.range.first
            var next = cut
            while (next < end && text[next].isWhitespace()) next++
            result.add(
                ReasoningFragment(text.substring(offset, cut), text.substring(cut, next), true),
            )
            offset = next
        }
        result.add(ReasoningFragment(text.substring(offset, end), suffix, complete))
    }
    for (match in paragraphBreak.findAll(text)) {
        paragraph(match.range.first, match.value, true)
        start = match.range.last + 1
    }
    paragraph(text.length, "", !running)
    return result
}

private val han = Regex("\\p{IsHan}")
private val latin = Regex("\\p{IsLatin}+")
private val otherLetters =
    Regex("(?!\\p{IsHan}|\\p{IsLatin}|\\p{IsGreek}|\\p{IsCommon})\\p{L}|\\p{IsGreek}{2,}")

fun reasoningNeedsTranslation(text: String, target: String): Boolean {
    if (otherLetters.containsMatchIn(text)) return true
    val chinese = han.findAll(text).count()
    val words = latin.findAll(text).count()
    return if (target.startsWith("zh", ignoreCase = true)) words > chinese else chinese > words
}
