package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

/** The snapshot describes todos at asOfSeq; historical pages cannot replace that state. */
internal class TodoProjection {
    private var seed: JsonArray? = null
    private var asOfSeq = -1L
    private var raw: JsonArray? = null
    var current: TodoList? = null
        private set

    fun seed(projections: JsonObject) {
        seed = (projections["values"] as? JsonObject)?.get("todos") as? JsonArray
        asOfSeq = projections.long("asOfSeq") ?: -1
    }

    fun apply(event: SessionRecord) {
        if (event.seq <= asOfSeq) return
        when (event.type) {
            "todo/write" -> replace(event.data["todos"] as? JsonArray ?: JsonArray(emptyList()))
            "turn/start" -> replace(null)
        }
    }

    fun rebuild(events: Iterable<SessionRecord>) {
        var latest = seed
        for (event in events) {
            if (event.seq <= asOfSeq) continue
            when (event.type) {
                "todo/write" -> latest = event.data["todos"] as? JsonArray ?: JsonArray(emptyList())
                "turn/start" -> latest = null
            }
        }
        replace(latest)
    }

    private fun replace(value: JsonArray?) {
        if (raw === value) return
        raw = value
        current = decodeTodoList(value)
    }
}
