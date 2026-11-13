package dev.fughetta.dshcompose.domain.messages

const val MAX_QUOTE_CHARACTERS = 1200

fun normalizeQuote(raw: String): String {
    val text =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    if (text.codePointCount(0, text.length) <= MAX_QUOTE_CHARACTERS) return text
    return text.substring(0, text.offsetByCodePoints(0, MAX_QUOTE_CHARACTERS)).trimEnd() + "…"
}

fun withQuote(quote: String?, text: String): String {
    val passage = quote?.let(::normalizeQuote).orEmpty()
    if (passage.isEmpty()) return text
    val block = passage.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    return if (text.isBlank()) block else block + "\n\n" + text.trim()
}

data class QuotedText(val quote: String?, val body: String)

fun splitQuote(text: String): QuotedText {
    val lines = text.replace("\r\n", "\n").split('\n')
    val pattern = Regex("^ {0,3}> ?")
    var index = 0
    val quoted = mutableListOf<String>()
    while (index < lines.size && pattern.containsMatchIn(lines[index])) {
        quoted.add(lines[index].replaceFirst(pattern, ""))
        index++
    }
    val quote = quoted.joinToString("\n").trim()
    if (quote.isEmpty()) return QuotedText(null, text)
    while (index < lines.size && lines[index].isBlank()) index++
    return QuotedText(quote, lines.drop(index).joinToString("\n").trimEnd())
}
