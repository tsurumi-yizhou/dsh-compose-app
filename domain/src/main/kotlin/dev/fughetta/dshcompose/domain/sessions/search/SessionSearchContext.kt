package dev.fughetta.dshcompose.domain.sessions.search

/** The selected host and the connection generation content search currently belongs to. */
data class SearchContext(val host: String = "", val connection: Long = 0)

class SessionSearchException(
    val code: String,
    val unavailable: Boolean = false,
    cause: Throwable? = null,
) : Exception(code, cause)
