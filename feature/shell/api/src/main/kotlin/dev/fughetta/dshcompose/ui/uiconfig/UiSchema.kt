package dev.fughetta.dshcompose.ui.uiconfig

// The fixed vocabulary of `$DSH_HOME/dsh-compose/ui.json` v1, read from the skill's own schema.json
// (app/src/main/assets/skills/dsh-compose-ui/), which the agent's checker check.mjs reads too: one
// list of
// values and limits for both validators.

import kotlinx.serialization.json.*

class UiSchema(
    val version: Int,
    val maxBytes: Int,
    val maxDepth: Int,
    private val limits: Map<String, Int>,
    val headerBuiltins: List<String>,
    val icons: List<String>,
    val drawerGroups: List<String>,
    val drawerSorts: List<String>,
    val drawerFilters: List<String>,
    val densities: List<String>,
    val hideItems: List<String>,
    val themeModes: List<String>,
    val accentPresets: List<String>,
    val seatModes: List<String>,
    val pages: List<String>,
    val actionTypes: List<String>,
    val shortcutKeys: List<String>,
    val systemModifiers: List<String>,
    val reservedShortcuts: List<String>,
    val knownSlots: Set<String>,
    val unseatableSlots: Set<String>,
) {
    private fun limit(name: String) = limits[name] ?: error("schema.json lacks limits.$name")

    val headerCustomMax = limit("headerCustomMax")
    val headerLabelMax = limit("headerLabelMax")
    val quickActionsMax = limit("quickActionsMax")
    val quickLabelMax = limit("quickLabelMax")
    val suggestionsMax = limit("suggestionsMax")
    val suggestionTitleMax = limit("suggestionTitleMax")
    val suggestionSubtitleMax = limit("suggestionSubtitleMax")
    val shortcutsMax = limit("shortcutsMax")
    val sendTextMax = limit("sendTextMax")
    val commandArgsMax = limit("commandArgsMax")
    val urlMax = limit("urlMax")

    companion object {
        /** Reads the skill's schema.json; throws when it is not the shape this app expects. */
        fun parse(text: String): UiSchema {
            val root = Json.parseToJsonElement(text).jsonObject
            val defs = root.getValue("\$defs").jsonObject
            val x = root.getValue("x-dsh-compose").jsonObject
            fun strings(value: JsonElement?) = (value as JsonArray).map { it.jsonPrimitive.content }

            fun enum(name: String) = strings(defs.getValue(name).jsonObject["enum"])
            val slots = x.getValue("slots").jsonObject
            return UiSchema(
                version = x.getValue("version").jsonPrimitive.int,
                maxBytes = x.getValue("maxBytes").jsonPrimitive.int,
                maxDepth = x.getValue("maxDepth").jsonPrimitive.int,
                limits = x.getValue("limits").jsonObject.mapValues { it.value.jsonPrimitive.int },
                headerBuiltins = enum("headerBuiltin"),
                icons = enum("icon"),
                drawerGroups = enum("drawerGroup"),
                drawerSorts = enum("drawerSort"),
                drawerFilters = enum("drawerFilter"),
                densities = enum("density"),
                hideItems = enum("hideItem"),
                themeModes = enum("themeMode"),
                accentPresets = enum("accentPreset"),
                seatModes = enum("seatMode"),
                pages = enum("page"),
                actionTypes = enum("actionType"),
                shortcutKeys = strings(x["shortcutKeys"]),
                systemModifiers = strings(x["systemModifiers"]),
                reservedShortcuts = strings(x["reservedShortcuts"]),
                knownSlots = strings(slots["known"]).toSet(),
                unseatableSlots = strings(slots["unseatable"]).toSet(),
            )
        }
    }
}
