package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sessions.TodoItem
import dev.fughetta.dshcompose.domain.sessions.TodoList
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

fun decodeTodoList(value: JsonArray?): TodoList? {
    if (value == null) return null
    return TodoList.fromItems(
        value.map { raw ->
            val row = raw as? JsonObject
            TodoItem(row?.string("content").orEmpty(), row?.string("status") ?: "pending")
        },
    )
}
