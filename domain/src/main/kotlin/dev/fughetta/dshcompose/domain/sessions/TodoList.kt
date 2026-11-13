package dev.fughetta.dshcompose.domain.sessions

import kotlinx.collections.immutable.*

data class TodoItem(val content: String, val status: String)

/** Computed when a todo list changes, shared unchanged across token deltas. */
class TodoList
private constructor(val items: PersistentList<TodoItem>, val done: Int, val currentIndex: Int) {
    val total
        get() = items.size

    val allDone
        get() = done == total

    val current
        get() = items.getOrNull(currentIndex)

    companion object {
        fun fromItems(values: List<TodoItem>): TodoList {
            val items = values.toPersistentList()
            var done = 0
            var active = -1
            var pending = -1
            for ((index, item) in items.withIndex()) {
                if (item.status == "completed") done++ else if (pending < 0) pending = index
                if (item.status == "in_progress" && active < 0) active = index
            }
            return TodoList(items, done, if (active >= 0) active else pending)
        }
    }
}
