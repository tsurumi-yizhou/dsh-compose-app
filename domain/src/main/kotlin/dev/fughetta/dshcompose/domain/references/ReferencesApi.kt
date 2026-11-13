package dev.fughetta.dshcompose.domain.references

data class ActiveReferenceQuery(
    val start: Int,
    val end: Int,
    val query: String,
    val quoted: Boolean,
) {
    val pathOnly
        get() = quoted || '/' in query
}

private val quotedReferenceQuery = Regex("(?:^|\\s)(@\"([^\"]*))$")
private val plainReferenceQuery = Regex("(?:^|\\s)(@([^\\s]*))$")
private val canonicalSessionMention =
    Regex("""@\[((?:\\.|[^\\\]\n])*)]\(dsh-session:[A-Za-z0-9_-]+\)""")

fun activeReferenceQuery(text: String, caret: Int): ActiveReferenceQuery? {
    if (caret !in 1..text.length) return null
    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    val before = text.substring(lineStart, caret)
    val match = quotedReferenceQuery.find(before) ?: plainReferenceQuery.find(before) ?: return null
    val start = lineStart + match.range.first + match.value.indexOf('@')
    val token = match.groupValues[1]
    if (
        canonicalSessionMention.matches(token) ||
            '"' in token.drop(if (token.startsWith("@\"")) 2 else 1)
    )
        return null
    return ActiveReferenceQuery(start, caret, match.groupValues[2], token.startsWith("@\""))
}
