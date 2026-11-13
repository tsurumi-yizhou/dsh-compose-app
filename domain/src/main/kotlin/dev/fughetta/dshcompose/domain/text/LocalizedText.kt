package dev.fughetta.dshcompose.domain.text

/** A text with translations by language id; `""` is the untranslated fallback. */
class LocalizedText(val values: Map<String, String>) {
    fun resolve(locale: String): String {
        val tag = locale.lowercase().replace('_', '-')
        val language = tag.substringBefore('-')
        fun languageOf(id: String) =
            values[id] ?: values.entries.firstOrNull { it.key.startsWith("$id-") }?.value
        val plain = values[""]
        val plainMatches =
            plain != null && plain.any { it in '\u3400'..'\u9fff' } == (language == "zh")
        return values[tag]
            ?: languageOf(language)
            ?: plain?.takeIf { plainMatches }
            ?: languageOf("en")
            ?: plain
            ?: values.values.first()
    }
}
