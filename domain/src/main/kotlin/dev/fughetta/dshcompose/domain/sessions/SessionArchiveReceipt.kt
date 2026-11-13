package dev.fughetta.dshcompose.domain.sessions

data class SessionArchiveReceipt<T>(val bytes: Long, val result: T)
