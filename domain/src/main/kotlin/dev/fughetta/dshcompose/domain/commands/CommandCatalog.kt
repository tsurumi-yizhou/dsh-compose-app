package dev.fughetta.dshcompose.domain.commands

import kotlinx.collections.immutable.PersistentList

class CommandOperationException(val code: String, cause: Throwable? = null) : Exception(code, cause)

data class CommandCatalog(
    val commands: PersistentList<SlashCommand>,
    val skills: PersistentList<SlashCommand>,
)
