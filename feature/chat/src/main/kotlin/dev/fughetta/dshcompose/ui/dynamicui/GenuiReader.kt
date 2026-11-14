package dev.fughetta.dshcompose.ui.dynamicui

import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

/** genui component names mapped to native nodes; controls keep their action names for replies. */
/** genui component names mapped to native nodes; controls keep their action names for replies. */
internal class GenuiReader {
    private val budget = GenuiBudget()

    fun parse(value: JsonElement): UiParse {
        val raw = value as? JsonObject
        val items =
            when {
                value is JsonArray -> value
                raw != null && (raw["type"] == null || raw["type"] is JsonNull) ->
                    raw["items"] as? JsonArray
                else -> JsonArray(listOf(value))
            }
        val children = GenuiMapper(budget).nodes(items, "spec.items", 2)
        val notes = budget.issues.toPersistentList()
        val root = UiLayout("spec", false, children)
        return UiParse(
            if (children.isEmpty()) null
            else UiView(genuiStr(raw?.get("title"))?.let { clip(it, 80) }, root, notes, "genui"),
            notes,
            "genui",
        )
    }
}
