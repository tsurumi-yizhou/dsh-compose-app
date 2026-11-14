package dev.fughetta.dshcompose.ui.dynamicui

import kotlinx.collections.immutable.PersistentList
import kotlinx.serialization.json.*

internal const val GenuiActionMarker = "[genui-action]"

internal enum class GenuiButtonStyle {
    Primary,
    Tonal,
    Danger,
    Ghost,
}

internal data class UiGenuiButton(
    override val path: String,
    val label: String,
    val style: GenuiButtonStyle,
    val action: String?,
) : UiNode

/** `checkbox` or `switch`; a grouped checkbox only records locally for a sibling submit. */
internal data class UiGenuiToggle(
    override val path: String,
    val label: String,
    val switch: Boolean,
    val checked: Boolean,
    val action: String?,
    val group: String?,
) : UiNode

internal enum class GenuiPickStyle {
    Select,
    Radio,
    Segmented,
}

/** `select`, `radio` or `segmented-control`; [key] is the field id, or the radio group. */
internal data class UiGenuiPick(
    override val path: String,
    val style: GenuiPickStyle,
    val label: String?,
    val options: PersistentList<String>,
    val selected: Int?,
    val action: String?,
    val key: String?,
    val answer: String? = null,
    val explanation: String? = null,
) : UiNode

internal data class UiGenuiChoice(
    val label: String,
    val hint: String?,
    val meta: String?,
    val recommended: Boolean,
    val disabled: Boolean,
    val reason: String?,
)

internal data class UiGenuiChoices(
    override val path: String,
    val title: String?,
    val prompt: String?,
    val items: PersistentList<UiGenuiChoice>,
    val multi: Boolean,
    val max: Int,
    val submit: String?,
    val action: String?,
    val group: String?,
    val answer: String?,
    val explanation: String?,
) : UiNode

internal data class UiGenuiQuizOption(
    val label: String,
    val correct: Boolean,
    val feedback: String?,
)

internal data class UiGenuiQuiz(
    override val path: String,
    val question: String,
    val options: PersistentList<UiGenuiQuizOption>,
    val explanation: String?,
    val action: String?,
) : UiNode

/** `input`, `textarea` or `editor`. Password inputs never become native fields. */
internal data class UiGenuiField(
    override val path: String,
    val type: String,
    val label: String?,
    val placeholder: String?,
    val value: String,
    val numeric: Boolean,
    val action: String?,
    val id: String?,
) : UiNode {
    val multiline
        get() = type != "input"

    val limit
        get() = if (type == "editor") 12000 else 2000
}

internal data class UiGenuiSlider(
    override val path: String,
    val label: String?,
    val min: Double,
    val max: Double,
    val step: Double,
    val value: Double,
    val action: String?,
    val id: String?,
) : UiNode

internal data class UiGenuiSubmit(
    override val path: String,
    val label: String,
    val action: String?,
    val resetAction: String?,
    val groups: PersistentList<String>?,
) : UiNode

/** Values a genui card collected locally for its submit buttons. */
internal sealed interface GenuiMember {
    val key: String
}

internal data class GenuiRadioMember(
    override val key: String,
    val label: String,
    val options: List<String>,
    val answer: String?,
    val explanation: String?,
) : GenuiMember

internal data class GenuiCheckMember(override val key: String, val choices: Set<String>) :
    GenuiMember

/** A field id owned by a [UiGenuiField], [UiGenuiPick] or [UiGenuiSlider]. */
internal data class GenuiFieldMember(override val key: String, val node: UiNode) : GenuiMember

internal data class GenuiSubmitState(
    val scope: List<GenuiMember>,
    val answered: Int,
    val total: Int,
    val localGrade: Boolean,
    val outOfScope: Boolean,
) {
    val ready
        get() = answered > 0 && answered >= total

    fun gradesLocally(action: String?) = localGrade && (!outOfScope || action == null)
}

/** Submission members in source order; a repeated key keeps its first owner, checkboxes merge. */
internal fun genuiMembers(view: UiView): Map<String, GenuiMember> {
    val members = linkedMapOf<String, GenuiMember>()
    for (node in view.root.walk()) {
        val member =
            when (node) {
                is UiGenuiPick ->
                    when {
                        node.key == null -> null
                        node.style == GenuiPickStyle.Radio ->
                            GenuiRadioMember(
                                node.key,
                                node.label ?: node.key,
                                node.options,
                                node.answer,
                                node.explanation,
                            )
                        else -> GenuiFieldMember(node.key, node)
                    }
                is UiGenuiChoices ->
                    node.group?.let { group ->
                        GenuiRadioMember(
                            group,
                            node.title ?: node.prompt ?: group,
                            node.items.map { it.label },
                            node.answer,
                            node.explanation,
                        )
                    }
                is UiGenuiToggle ->
                    node.group
                        ?.takeIf { !node.switch }
                        ?.let { GenuiCheckMember(it, setOf(node.label)) }
                is UiGenuiField -> node.id?.let { GenuiFieldMember(it, node) }
                is UiGenuiSlider -> node.id?.let { GenuiFieldMember(it, node) }
                else -> null
            } ?: continue
        val existing = members[member.key]
        if (existing == null) members[member.key] = member
        else if (existing is GenuiCheckMember && member is GenuiCheckMember)
            members[member.key] = existing.copy(choices = existing.choices + member.choices)
    }
    return members
}

/** Model-provided defaults are real initial answers, exactly as the web card registers them. */
internal fun genuiDefaults(view: UiView): GenuiState {
    val answers = linkedMapOf<String, String>()
    val multi = linkedMapOf<String, List<String>>()
    val fields = linkedMapOf<String, String>()
    for (node in view.root.walk()) when (node) {
        is UiGenuiPick -> {
            val key = node.key ?: continue
            val value = node.selected?.let(node.options::getOrNull) ?: continue
            if (node.style == GenuiPickStyle.Radio) answers.putIfAbsent(key, value)
            else fields.putIfAbsent(key, value)
        }
        is UiGenuiToggle ->
            if (node.group != null && !node.switch && node.checked)
                multi[node.group] = (multi[node.group].orEmpty() + node.label).distinct()
        is UiGenuiSlider -> node.id?.let { fields.putIfAbsent(it, jsNumber(node.value)) }
        is UiGenuiField ->
            if (node.id != null && node.value.isNotBlank()) fields.putIfAbsent(node.id, node.value)
        else -> Unit
    }
    return GenuiState(answers, multi, fields)
}

private fun GenuiMember.answered(state: GenuiState) =
    when (this) {
        is GenuiRadioMember -> state.answers[key] != null
        is GenuiCheckMember -> state.multi[key]?.any { it in choices } == true
        is GenuiFieldMember -> state.fields[key]?.isNotBlank() == true
    }

internal fun genuiSubmitState(
    members: Map<String, GenuiMember>,
    groups: List<String>?,
    state: GenuiState,
): GenuiSubmitState {
    val scope = groups?.mapNotNull(members::get) ?: members.values.filter { it.answered(state) }
    val keys = scope.mapTo(hashSetOf()) { it.key }
    val outOfScope =
        state.answers.keys.any { it !in keys } ||
            state.multi.any { (key, values) -> values.isNotEmpty() && key !in keys } ||
            state.fields.any { (key, value) -> value.isNotBlank() && key !in keys }
    return GenuiSubmitState(
        scope,
        scope.count { it.answered(state) },
        groups?.size ?: scope.size,
        groups.orEmpty().all(members::containsKey) &&
            scope.isNotEmpty() &&
            scope.all { it is GenuiRadioMember } &&
            scope.any { (it as GenuiRadioMember).answer != null },
        outOfScope,
    )
}

/** Same text `String(number)` gives in the web card, so slider values read alike. */
internal fun jsNumber(value: Double): String =
    if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
    else value.toString()

private fun jsonNumber(value: Double): JsonPrimitive =
    if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong())
    else JsonPrimitive(value)

private fun GenuiFieldMember.accepts(value: String): Boolean =
    when (val owner = node) {
        is UiGenuiField ->
            value.length <= owner.limit &&
                (!owner.numeric || value.isBlank() || value.trim().toDoubleOrNull() != null)
        is UiGenuiPick -> value in owner.options
        is UiGenuiSlider -> value.toDoubleOrNull()?.let { it in owner.min..owner.max } == true
        else -> false
    }

/** Rejects anything the current source could not have produced. */
private fun GenuiState.validIn(members: Map<String, GenuiMember>): Boolean =
    answers.all { (key, value) ->
        (members[key] as? GenuiRadioMember)?.options?.contains(value) == true
    } &&
        multi.all { (key, values) ->
            val member = members[key] as? GenuiCheckMember
            member != null &&
                values.distinct().size == values.size &&
                member.choices.containsAll(values)
        } &&
        fields.all { (key, value) -> (members[key] as? GenuiFieldMember)?.accepts(value) == true }

private fun JsonObjectBuilder.id(id: String?) {
    if (id != null) put("id", id)
}

/**
 * The action name and component data the web card would send for [input], rebuilt from [view] only;
 * null when the source cannot produce it or the click is graded locally.
 */
internal fun genuiAction(view: UiView, input: GenuiInput): Pair<String, JsonObject>? {
    if (view.dialect != "genui") return null
    val node = view.root.walk().firstOrNull { it.path == input.path } ?: return null
    val single = input.value?.takeIf { input.values.isEmpty() }
    return when (node) {
        is UiGenuiButton ->
            node.action?.let {
                it to
                    buildJsonObject {
                        put("type", "button")
                        put("label", node.label)
                    }
            }
        is UiGenuiToggle -> {
            val checked = input.checked ?: return null
            if (node.group != null && !node.switch) return null
            node.action?.let {
                it to
                    buildJsonObject {
                        put("type", if (node.switch) "switch" else "checkbox")
                        put("checked", checked)
                    }
            }
        }
        is UiGenuiPick -> {
            val action = node.action ?: return null
            if (single == null || single !in node.options) return null
            if (node.style == GenuiPickStyle.Radio && node.key != null) return null
            action to
                buildJsonObject {
                    when (node.style) {
                        GenuiPickStyle.Radio -> {
                            put("type", "radio")
                            put("value", single)
                        }
                        GenuiPickStyle.Select -> {
                            put("type", "select")
                            put("value", single)
                            id(node.key)
                        }
                        GenuiPickStyle.Segmented -> {
                            put("type", "segmented-control")
                            put("value", single)
                            id(node.key)
                        }
                    }
                }
        }
        is UiGenuiSlider -> {
            val action = node.action ?: return null
            val value = single?.toDoubleOrNull()?.takeIf { it in node.min..node.max } ?: return null
            action to
                buildJsonObject {
                    put("type", "slider")
                    put("value", jsonNumber(value))
                    id(node.id)
                }
        }
        is UiGenuiField -> {
            val action = node.action ?: return null
            val value = single ?: return null
            if (
                node.type == "editor" ||
                    value.length > node.limit ||
                    node.numeric && value.isNotBlank() && value.trim().toDoubleOrNull() == null
            )
                return null
            action to
                buildJsonObject {
                    put("type", node.type)
                    put("value", value)
                    id(node.id)
                    if (input.submit) put("submit", true)
                }
        }
        is UiGenuiChoices -> {
            val action = node.action ?: return null
            val labels = input.values
            val allowed = node.items.filterNot { it.disabled }.map { it.label }
            if (
                node.group != null ||
                    node.submit == null ||
                    labels.isEmpty() ||
                    labels.size > node.max ||
                    labels.distinct().size != labels.size ||
                    !allowed.containsAll(labels)
            )
                return null
            action to
                buildJsonObject {
                    put("question", node.prompt ?: node.title ?: "")
                    put("selected", JsonArray(labels.map(::JsonPrimitive)))
                    put("mode", if (node.multi) "multi" else "single")
                }
        }
        is UiGenuiQuiz -> {
            val action = node.action ?: return null
            val option = node.options.firstOrNull { it.label == single } ?: return null
            action to
                buildJsonObject {
                    put("type", "quiz")
                    put("question", node.question)
                    put("answer", option.label)
                    put("correct", option.correct)
                }
        }
        is UiGenuiSubmit -> submitAction(view, node, input)
        else -> null
    }
}

private fun submitAction(
    view: UiView,
    node: UiGenuiSubmit,
    input: GenuiInput,
): Pair<String, JsonObject>? {
    val members = genuiMembers(view)
    val state = input.state
    if (!state.validIn(members)) return null
    if (input.reset) {
        val action = node.resetAction ?: return null
        return action to
            buildJsonObject {
                put("type", "submit-reset")
                put(
                    "groups",
                    JsonArray((node.groups ?: state.answers.keys.toList()).map(::JsonPrimitive)),
                )
            }
    }
    val action = node.action ?: return null
    val resolved = genuiSubmitState(members, node.groups, state)
    if (!resolved.ready || resolved.gradesLocally(action)) return null
    val filled = state.fields.filterValues { it.isNotBlank() }
    return action to
        buildJsonObject {
            put("type", "submit")
            putJsonObject("answers") {
                for ((key, value) in state.answers) put(key, value)
                for ((key, values) in state.multi) put(key, JsonArray(values.map(::JsonPrimitive)))
            }
            if (filled.isNotEmpty())
                putJsonObject("fields") { for ((key, value) in filled) put(key, value) }
            put("total", resolved.total)
            put("answered", resolved.answered)
        }
}

/** The `[genui-action]` user message; [format] receives the action and the compact JSON. */
internal fun genuiActionText(
    view: UiView,
    input: GenuiInput,
    format: (String, String) -> String,
): String? {
    val (action, payload) = genuiAction(view, input) ?: return null
    return format(action, payload.toString())
}
