package dev.fughetta.dshcompose.ui.dynamicui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.Spacing
import kotlin.math.roundToInt

/** Local answers of one genui card; selecting, typing and grading never need a round trip. */
@Stable
internal class GenuiCardState(view: UiView) {
    val members = genuiMembers(view)
    private val defaults = genuiDefaults(view)
    val answers = mutableStateMapOf<String, String>()
    val multi = mutableStateMapOf<String, List<String>>()
    val fields = mutableStateMapOf<String, String>()
    /** Values of controls without a submission key, by node path. */
    val local = mutableStateMapOf<String, String>()
    val picks = mutableStateMapOf<String, List<String>>()
    val sent = mutableStateMapOf<String, String>()
    var locked by mutableStateOf(false)

    init {
        reset()
    }

    fun snapshot() = GenuiState(answers.toMap(), multi.toMap(), fields.toMap())

    fun reset() {
        fun <V> SnapshotStateMap<String, V>.restore(values: Map<String, V>) {
            clear()
            putAll(values)
        }
        answers.restore(defaults.answers)
        multi.restore(defaults.multi)
        fields.restore(defaults.fields)
        locked = false
    }
}

internal data class GenuiScope(
    val card: GenuiCardState,
    val canSend: Boolean,
    val send: (GenuiInput) -> Unit,
)

internal val LocalGenui = staticCompositionLocalOf<GenuiScope?> { null }

internal fun UiNode.genuiAction(): String? =
    when (this) {
        is UiGenuiButton -> action
        is UiGenuiToggle -> action?.takeIf { group == null || switch }
        is UiGenuiPick -> action?.takeIf { style != GenuiPickStyle.Radio || key == null }
        is UiGenuiChoices -> action?.takeIf { group == null && submit != null }
        is UiGenuiQuiz -> action
        is UiGenuiField -> action
        is UiGenuiSlider -> action
        is UiGenuiSubmit -> action ?: resetAction
        else -> null
    }

@Composable
internal fun GenuiControl(node: UiNode) {
    val scope = LocalGenui.current ?: return
    when (node) {
        is UiGenuiButton -> GenuiButton(node, scope)
        is UiGenuiToggle -> GenuiToggle(node, scope)
        is UiGenuiPick -> GenuiPick(node, scope)
        is UiGenuiChoices -> GenuiChoices(node, scope)
        is UiGenuiQuiz -> GenuiQuiz(node, scope)
        is UiGenuiField -> GenuiField(node, scope)
        is UiGenuiSlider -> GenuiSlider(node, scope)
        is UiGenuiSubmit -> GenuiSubmit(node, scope)
        else -> Unit
    }
}

@Composable
private fun Label(text: String?) {
    text?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A control that only reports through its action is usable only while the card can send. */
private fun usable(action: String?, scope: GenuiScope) = action == null || scope.canSend

@Composable
private fun GenuiButton(node: UiGenuiButton, scope: GenuiScope) {
    val enabled = node.action != null && scope.canSend
    val click = { scope.send(GenuiInput(node.path)) }
    val label = @Composable { Text(node.label) }
    when (node.style) {
        GenuiButtonStyle.Primary -> Button(click, enabled = enabled) { label() }
        GenuiButtonStyle.Danger ->
            Button(
                click,
                enabled = enabled,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                label()
            }
        GenuiButtonStyle.Ghost -> TextButton(click, enabled = enabled) { label() }
        GenuiButtonStyle.Tonal -> FilledTonalButton(click, enabled = enabled) { label() }
    }
}

@Composable
private fun GenuiToggle(node: UiGenuiToggle, scope: GenuiScope) {
    val card = scope.card
    val grouped = node.group != null && !node.switch
    val checked =
        if (grouped) card.multi[node.group].orEmpty().contains(node.label)
        else card.local[node.path]?.toBooleanStrict() ?: node.checked
    val enabled = if (grouped) !card.locked else usable(node.action, scope)
    val change = { next: Boolean ->
        if (grouped) {
            val current = card.multi[node.group].orEmpty() - node.label
            card.multi[node.group!!] = if (next) current + node.label else current
        } else {
            card.local[node.path] = next.toString()
            if (node.action != null) scope.send(GenuiInput(node.path, checked = next))
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                checked,
                enabled = enabled,
                role = if (node.switch) Role.Switch else Role.Checkbox,
                onValueChange = change,
            ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.switch) {
            Text(node.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked, onCheckedChange = null, enabled = enabled)
        } else {
            Checkbox(checked, onCheckedChange = null, enabled = enabled)
            Text(node.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GenuiPick(node: UiGenuiPick, scope: GenuiScope) {
    val card = scope.card
    val paper = node.style == GenuiPickStyle.Radio && node.key != null
    val value =
        when {
            node.key == null -> card.local[node.path] ?: node.selected?.let(node.options::getOrNull)
            paper -> card.answers[node.key]
            else -> card.fields[node.key]
        }
    val enabled = if (paper) !card.locked else usable(node.action, scope)
    val choose = { option: String ->
        when {
            node.key == null -> card.local[node.path] = option
            paper -> card.answers[node.key] = option
            else -> card.fields[node.key] = option
        }
        if (!paper && node.action != null) scope.send(GenuiInput(node.path, value = option))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Label(node.label)
        when (node.style) {
            GenuiPickStyle.Radio ->
                Column(Modifier.selectableGroup()) {
                    for (option in node.options) Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                option == value,
                                enabled = enabled,
                                role = Role.RadioButton,
                            ) {
                                choose(option)
                            }
                            .padding(vertical = Spacing.ExtraSmall),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(option == value, onClick = null, enabled = enabled)
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            GenuiPickStyle.Segmented ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    for (option in node.options) FilterChip(
                        selected = option == value,
                        onClick = { choose(option) },
                        label = { Text(option) },
                        enabled = enabled,
                    )
                }
            GenuiPickStyle.Select -> {
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = enabled) {
                        Text(value ?: stringResource(R.string.view_genui_select))
                    }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        for (option in node.options) DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                open = false
                                choose(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenuiChoices(node: UiGenuiChoices, scope: GenuiScope) {
    val card = scope.card
    val paper = node.group != null
    val selected =
        if (paper) listOfNotNull(card.answers[node.group]) else card.picks[node.path].orEmpty()
    val done = card.sent[node.path] != null
    val toggle = { label: String ->
        if (paper) card.answers[node.group!!] = label
        else {
            card.sent.remove(node.path)
            card.picks[node.path] =
                when {
                    label in selected -> selected - label
                    !node.multi -> listOf(label)
                    selected.size >= node.max -> selected
                    else -> selected + label
                }
        }
    }
    Column(
        if (node.multi) Modifier else Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        node.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        node.prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        for (item in node.items) {
            val active = item.label in selected
            val enabled = !item.disabled && !(paper && card.locked)
            Row(
                Modifier.fillMaxWidth()
                    .then(
                        if (node.multi)
                            Modifier.toggleable(active, enabled = enabled, role = Role.Checkbox) {
                                toggle(item.label)
                            }
                        else
                            Modifier.selectable(
                                active,
                                enabled = enabled,
                                role = Role.RadioButton,
                            ) {
                                toggle(item.label)
                            },
                    )
                    .padding(vertical = Spacing.ExtraSmall),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                if (node.multi) Checkbox(active, onCheckedChange = null, enabled = enabled)
                else RadioButton(active, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                        Text(item.label, style = MaterialTheme.typography.bodyLarge)
                        if (item.recommended)
                            Text(
                                stringResource(R.string.view_genui_recommended),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        if (item.disabled)
                            Text(
                                stringResource(R.string.view_genui_unavailable),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    (if (item.disabled) item.reason else item.hint)?.let { Muted(it) }
                }
                item.meta?.let { Muted(it) }
            }
        }
        if (!paper && node.submit != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        card.sent[node.path] = ""
                        if (node.action != null)
                            scope.send(GenuiInput(node.path, values = selected))
                    },
                    enabled = selected.isNotEmpty() && !done && usable(node.action, scope),
                ) {
                    Text(node.submit)
                }
                if (done)
                    Muted(
                        stringResource(
                            if (node.action == null) R.string.view_genui_local
                            else R.string.view_genui_sent,
                        ),
                    )
            }
        }
    }
}

@Composable
private fun GenuiQuiz(node: UiGenuiQuiz, scope: GenuiScope) {
    val card = scope.card
    val chosen = card.picks[node.path]?.firstOrNull()
    val answer = node.options.firstOrNull { it.label == chosen }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(node.question, style = MaterialTheme.typography.titleSmall)
        for (option in node.options) {
            val mark =
                when {
                    answer == null -> if (option.label == chosen) "● " else "○ "
                    option.correct -> "✓ "
                    option.label == chosen -> "✗ "
                    else -> ""
                }
            OutlinedButton(
                onClick = {
                    card.picks[node.path] = listOf(option.label)
                    if (node.action != null && scope.canSend)
                        scope.send(GenuiInput(node.path, value = option.label))
                },
                enabled = answer == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(mark + option.label, Modifier.fillMaxWidth())
            }
        }
        if (answer != null) {
            Text(
                stringResource(
                    if (answer.correct) R.string.view_genui_correct else R.string.view_genui_wrong,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = toneColor(if (answer.correct) UiTone.Good else UiTone.Warn),
            )
            answer.feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            node.explanation?.let { Muted(it) }
            TextButton(onClick = { card.picks.remove(node.path) }) {
                Text(stringResource(R.string.view_genui_retry))
            }
        }
    }
}

@Composable
private fun GenuiField(node: UiGenuiField, scope: GenuiScope) {
    val card = scope.card
    val value = node.id?.let { card.fields[it] } ?: card.local[node.path] ?: node.value
    val invalid = node.numeric && value.isNotBlank() && value.trim().toDoubleOrNull() == null
    val ready =
        node.action != null &&
            scope.canSend &&
            !invalid &&
            value.isNotBlank() &&
            value != card.sent[node.path]
    val send = {
        card.sent[node.path] = value
        scope.send(GenuiInput(node.path, value = value, submit = true))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                val kept = next.take(node.limit)
                if (node.id != null) card.fields[node.id] = kept else card.local[node.path] = kept
            },
            modifier = Modifier.fillMaxWidth(),
            label = node.label?.let { { Text(it) } },
            placeholder = node.placeholder?.let { { Text(it) } },
            isError = invalid,
            supportingText =
                if (invalid) {
                    { Text(stringResource(R.string.view_genui_number)) }
                } else null,
            singleLine = !node.multiline,
            minLines = if (node.type == "editor") 4 else 1,
            maxLines = if (node.multiline) 12 else 1,
            textStyle =
                if (node.type == "editor")
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                else LocalTextStyle.current,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (node.numeric) KeyboardType.Decimal else KeyboardType.Text,
                    imeAction =
                        if (!node.multiline && node.action != null) ImeAction.Send
                        else ImeAction.Default,
                ),
            keyboardActions = KeyboardActions(onSend = { if (ready) send() }),
        )
        if (node.action != null)
            TextButton(onClick = send, enabled = ready) { Text(stringResource(R.string.send)) }
    }
}

@Composable
private fun GenuiSlider(node: UiGenuiSlider, scope: GenuiScope) {
    val card = scope.card
    fun current() =
        (node.id?.let { card.fields[it] } ?: card.local[node.path])?.toDoubleOrNull() ?: node.value
    val value = current()
    val intervals = ((node.max - node.min) / node.step).roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Label(node.label) }
            Text(jsNumber(value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped =
                    (node.min + ((raw - node.min) / node.step).roundToInt() * node.step).coerceIn(
                        node.min,
                        node.max,
                    )
                val text = jsNumber(snapped)
                if (node.id != null) card.fields[node.id] = text else card.local[node.path] = text
            },
            onValueChangeFinished = {
                if (node.action != null)
                    scope.send(GenuiInput(node.path, value = jsNumber(current())))
            },
            valueRange = node.min.toFloat()..node.max.toFloat(),
            steps = if (intervals in 2..200) intervals - 1 else 0,
            enabled = usable(node.action, scope) && node.max > node.min,
        )
    }
}

@Composable
private fun GenuiSubmit(node: UiGenuiSubmit, scope: GenuiScope) {
    val card = scope.card
    val state = card.snapshot()
    val resolved = genuiSubmitState(card.members, node.groups, state)
    if (card.locked) {
        Grade(resolved, state)
        TextButton(
            onClick = {
                card.reset()
                if (node.resetAction != null && scope.canSend)
                    scope.send(GenuiInput(node.path, reset = true, state = state))
            },
        ) {
            Text(stringResource(R.string.view_genui_retry))
        }
        return
    }
    val local = resolved.gradesLocally(node.action)
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = {
                if (local) card.locked = true else scope.send(GenuiInput(node.path, state = state))
            },
            enabled = resolved.ready && (local || node.action != null && scope.canSend),
        ) {
            Text(node.label)
        }
        if (resolved.total > 0)
            Muted(stringResource(R.string.view_genui_progress, resolved.answered, resolved.total))
    }
}

@Composable
private fun Grade(resolved: GenuiSubmitState, state: GenuiState) {
    val graded =
        resolved.scope.filterIsInstance<GenuiRadioMember>().filter {
            state.answers[it.key] != null && it.answer != null
        }
    val score = graded.count { state.answers[it.key] == it.answer }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            "${stringResource(R.string.view_genui_score)} $score / ${graded.size}",
            style = MaterialTheme.typography.titleMedium,
        )
        for (member in resolved.scope.filterIsInstance<GenuiRadioMember>()) {
            val entry = state.answers[member.key] ?: continue
            val correct = member.answer
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
                Text(
                    (if (correct == null) "" else if (entry == correct) "✓ " else "✗ ") +
                        member.label,
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        when {
                            correct == null -> MaterialTheme.colorScheme.onSurface
                            entry == correct -> toneColor(UiTone.Good)
                            else -> toneColor(UiTone.Bad)
                        },
                )
                Text(
                    stringResource(R.string.view_genui_your_answer, entry),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (correct != null && entry != correct)
                    Text(
                        stringResource(R.string.view_genui_correct_answer, correct),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                member.explanation?.let { Muted(it) }
            }
        }
    }
}
