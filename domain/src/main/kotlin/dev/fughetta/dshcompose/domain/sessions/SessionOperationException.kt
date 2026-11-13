package dev.fughetta.dshcompose.domain.sessions

class SessionOperationException(val code: String, cause: Throwable? = null) : Exception(code, cause)
