package dev.fughetta.dshcompose.ui.dynamicui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.extendedColors
import dev.fughetta.dshcompose.ui.markdown.MarkdownMessageText

internal data class UiSubmission(
    val path: String,
    val selected: List<Int> = emptyList(),
    val freetext: String? = null,
    val confirmed: Boolean? = null,
)

internal data class DynamicUiContext(
    val state: UiInteractionState = UiInteractionState(UiInteraction.ReadOnly),
    val streaming: Boolean = false,
    val pending: UiReply? = null,
    val busy: Boolean = false,
    val onReply: ((String, UiSubmission) -> Unit)? = null,
    val allowViews: Boolean = true,
    val submitted: Boolean = false,
    val onResend: ((String) -> Unit)? = null,
    val onGenuiAction: ((String, GenuiInput) -> Unit)? = null,
)

internal val LocalDynamicUi = staticCompositionLocalOf { DynamicUiContext() }

@Composable
internal fun DynamicUiCard(source: String, closed: Boolean, parsed: UiParse?) {
    val context = LocalDynamicUi.current
    if (!closed) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            Text(
                stringResource(
                    if (context.streaming) R.string.view_generating else R.string.view_incomplete,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            if (context.streaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
    val view = parsed?.view
    if (view == null) {
        Text(
            stringResource(
                if (parsed?.dialect != null) R.string.view_dialect_unavailable
                else R.string.view_invalid,
            ),
            color =
                if (parsed?.dialect != null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
        )
        parsed?.issues?.forEach { Issue(it) }
        if (
            parsed?.dialect == null &&
                context.onResend != null &&
                (context.state.mode == UiInteraction.Live || context.submitted)
        ) {
            TextButton(
                onClick = { context.onResend.invoke(source) },
                enabled = !context.submitted && !context.busy,
            ) {
                Text(
                    stringResource(
                        if (context.submitted && !context.busy) R.string.view_resent
                        else R.string.view_resend,
                    ),
                )
            }
            if (context.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
    val reply = context.state.reply ?: context.pending
    val answer = remember(view, reply) { reply?.let { answerFor(view, it) } }
    val enabled =
        view.dialect == null &&
            context.state.mode == UiInteraction.Live &&
            reply == null &&
            !context.submitted &&
            !context.busy &&
            context.onReply != null
    val genui = remember(view) { if (view.dialect == "genui") GenuiCardState(view) else null }
    val canSend =
        context.state.mode == UiInteraction.Live &&
            !context.submitted &&
            !context.busy &&
            context.onGenuiAction != null
    val scope = genui?.let { card ->
        GenuiScope(card, canSend) { input ->
            if (canSend) context.onGenuiAction?.invoke(source, input)
        }
    }
    UiCardPictures(view) {
        CompositionLocalProvider(LocalGenui provides scope) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                ) {
                    view.title?.let {
                        Text(
                            it,
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Node(view.root, answer, enabled) { context.onReply?.invoke(source, it) }
                    if (context.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (genui != null && view.root.walk().any { it.genuiAction() != null }) {
                        val caption =
                            when {
                                context.submitted || context.state.mode == UiInteraction.Answered ->
                                    R.string.view_genui_sent
                                context.state.mode == UiInteraction.Running -> R.string.view_running
                                context.state.mode == UiInteraction.Expired -> R.string.view_expired
                                context.state.mode == UiInteraction.ReadOnly ->
                                    R.string.view_readonly
                                else -> null
                            }
                        caption?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (view.root.walk().any { it is UiOptions || it is UiConfirm }) {
                        val caption =
                            when {
                                reply != null && answer != null -> R.string.view_answered
                                view.dialect != null -> R.string.view_readonly
                                context.state.mode == UiInteraction.Running -> R.string.view_running
                                context.state.mode in
                                    setOf(UiInteraction.Expired, UiInteraction.Answered) ->
                                    R.string.view_expired
                                context.state.mode == UiInteraction.ReadOnly ->
                                    R.string.view_readonly
                                else -> null
                            }
                        caption?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (view.issues.isNotEmpty()) {
                        var expanded by remember(view) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(R.string.view_adjusted))
                        }
                        if (expanded) view.issues.forEach { Issue(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Issue(issue: UiIssue) {
    Text(
        "${issue.path}: ${stringResource(issue.kind.labelResource())}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Node(
    node: UiNode,
    answer: UiAnswer?,
    enabled: Boolean,
    submit: (UiSubmission) -> Unit,
) {
    when (node) {
        is UiLayout ->
            if (node.row && node.weights != null)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 120.dp * node.children.size)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                        ) {
                            node.children.forEachIndexed { index, item ->
                                Box(Modifier.weight(node.weights[index])) {
                                    Node(item, answer, enabled, submit)
                                }
                            }
                        }
                    else
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                            node.children.forEach { Node(it, answer, enabled, submit) }
                        }
                }
            else if (node.row)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                ) {
                    node.children.forEach { child ->
                        Box(Modifier.widthIn(min = 120.dp, max = 320.dp)) {
                            Node(child, answer, enabled, submit)
                        }
                    }
                }
            else
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    node.children.forEach { Node(it, answer, enabled, submit) }
                }
        is UiText ->
            if (node.rich != null)
                RestrictedTextView(node.rich, node.appearance, node.muted, Modifier.fillMaxWidth())
            else
                CompositionLocalProvider(
                    LocalDynamicUi provides DynamicUiContext(allowViews = false),
                    LocalContentColor provides
                        if (node.muted) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                ) {
                    MarkdownMessageText(node.text, ReferenceLabelIndex.Empty, copySource = false)
                }
        is UiMetric ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
                Text(node.label, style = MaterialTheme.typography.labelLarge)
                Text(
                    node.boolean?.let {
                        stringResource(if (it) R.string.view_yes else R.string.view_no)
                    } ?: node.value,
                    style =
                        if (node.display) MaterialTheme.typography.headlineLarge
                        else MaterialTheme.typography.headlineMedium,
                    color = toneColor(node.tone),
                )
                node.caption?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                node.trend?.let { UiTrend(it, node.tone) }
            }
        is UiChart -> DynamicUiChart(node)
        is UiCompare ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                for (item in node.items) OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(Spacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                    ) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        item.badge?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        for ((label, value) in item.rows) Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                        ) {
                            Text(
                                label,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                value,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        is UiSteps ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                for ((index, step) in node.items.withIndex()) Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                ) {
                    val status =
                        when (step.status) {
                            UiStepStatus.Done -> R.string.view_step_done
                            UiStepStatus.Current -> R.string.view_step_current
                            UiStepStatus.Todo -> R.string.view_step_todo
                            UiStepStatus.Failed -> R.string.view_step_failed
                        }
                    Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
                    ) {
                        Text(step.label, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(status), style = MaterialTheme.typography.labelMedium)
                        step.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        is UiCallout ->
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        toneColor(node.tone).copy(alpha = 0.1f),
                        MaterialTheme.shapes.medium,
                    )
                    .padding(Spacing.Medium),
            ) {
                CompositionLocalProvider(
                    LocalDynamicUi provides DynamicUiContext(allowViews = false),
                ) {
                    MarkdownMessageText(node.text, ReferenceLabelIndex.Empty, copySource = false)
                }
            }
        is UiOptions -> Options(node, answer?.takeIf { it.path == node.path }, enabled, submit)
        is UiConfirm ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                val recorded = answer?.takeIf { it.path == node.path }?.confirmed
                FilledTonalButton(
                    onClick = { submit(UiSubmission(node.path, confirmed = false)) },
                    enabled = enabled,
                ) {
                    Text(
                        (if (recorded == false) "✓ " else "") +
                            (node.cancel ?: stringResource(R.string.cancel)),
                    )
                }
                Button(
                    onClick = { submit(UiSubmission(node.path, confirmed = true)) },
                    enabled = enabled,
                ) {
                    Text(
                        (if (recorded == true) "✓ " else "") +
                            (node.confirm ?: stringResource(R.string.confirm)),
                    )
                }
            }
        is UiPlaceholder -> Issue(node.issue)
        is UiGenuiButton,
        is UiGenuiToggle,
        is UiGenuiPick,
        is UiGenuiChoices,
        is UiGenuiQuiz,
        is UiGenuiField,
        is UiGenuiSlider,
        is UiGenuiSubmit -> GenuiControl(node)
        is UiPanel,
        is UiGrid,
        is UiBrowse,
        is UiList,
        is UiTable,
        is UiProgress,
        is UiCode,
        is UiPicture,
        is UiTag,
        is UiWebOnly,
        is UiDivider,
        is UiSpacer -> GenuiContent(node) { Node(it, answer, enabled, submit) }
    }
}

@Composable
private fun Options(
    node: UiOptions,
    answer: UiAnswer?,
    enabled: Boolean,
    submit: (UiSubmission) -> Unit,
) {
    var picked by remember(node) { mutableStateOf(emptySet<Int>()) }
    var text by remember(node) { mutableStateOf("") }
    val selected = answer?.selected ?: picked
    Column(
        if (node.multi) Modifier else Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        for ((index, option) in node.options.withIndex()) {
            val control =
                if (node.multi)
                    Modifier.toggleable(
                        index in selected,
                        enabled = enabled,
                        role = Role.Checkbox,
                    ) { checked ->
                        picked = if (checked) picked + index else picked - index
                    }
                else
                    Modifier.selectable(
                        index in selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                    ) {
                        picked = setOf(index)
                    }
            Row(
                Modifier.fillMaxWidth().then(control).padding(vertical = Spacing.ExtraSmall),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                if (node.multi)
                    Checkbox(index in selected, onCheckedChange = null, enabled = enabled)
                else RadioButton(index in selected, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    option.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (node.freetext)
            OutlinedTextField(
                value = answer?.freetext ?: text,
                onValueChange = { text = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text(node.hint ?: stringResource(R.string.view_other)) },
                minLines = 1,
                maxLines = 4,
            )
        Button(
            onClick = {
                submit(
                    UiSubmission(
                        node.path,
                        picked.sorted(),
                        text.takeIf { node.freetext && it.isNotBlank() },
                    ),
                )
            },
            enabled = enabled && (picked.isNotEmpty() || node.freetext && text.isNotBlank()),
        ) {
            Text(stringResource(R.string.send))
        }
    }
}

@Composable
internal fun toneColor(tone: UiTone): Color =
    when (tone) {
        UiTone.Bad -> MaterialTheme.colorScheme.error
        UiTone.Warn -> MaterialTheme.extendedColors.warning
        UiTone.Good -> MaterialTheme.extendedColors.success
        UiTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        UiTone.Info -> MaterialTheme.colorScheme.onSurface
    }
