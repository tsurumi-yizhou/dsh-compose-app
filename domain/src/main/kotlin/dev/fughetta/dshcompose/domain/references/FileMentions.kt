package dev.fughetta.dshcompose.domain.references

data class FileReferenceCandidate(val path: String, val isDirectory: Boolean)

/** The engine's file-reference grammar leaves quoted directory references open after the slash. */
fun formatFileMention(path: String, directory: Boolean, preserveQuote: Boolean = false): String? {
    val value = if (directory) "$path/" else path
    if (value.any { it.code in 0..31 || it.code in 127..159 || it == '"' }) return null
    if (!preserveQuote && value.none(Char::isWhitespace)) return "@$value"
    return if (directory) "@\"$value" else "@\"$value\""
}

/** One conversation the user can reference in the composer. */
data class SessionReferenceCandidate(
    val sessionId: String,
    val title: String,
    val mention: String,
    val sameWorkspace: Boolean,
    val cwd: String?,
)
