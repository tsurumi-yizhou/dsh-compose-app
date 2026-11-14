package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.chat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsDialog(state: SkillsUiState, onAction: (AppAction) -> Unit) {
    LifecycleStartEffect(state.sessionId) {
        onAction(AppAction.ObserveSkills(true))
        onStopOrDispose { onAction(AppAction.ObserveSkills(false)) }
    }
    val selected = state.selected
    fun close() =
        onAction(if (selected == null) AppAction.CloseSkills else AppAction.CloseSkillDetail)
    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(CoreDesignsystemR.string.skills_title)) },
                    actions = {
                        TextButton(
                            onClick = { onAction(AppAction.RefreshSkills) },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(CoreDesignsystemR.string.refresh))
                        }
                        TextButton(onClick = { close() }, enabled = !state.busy) {
                            Text(stringResource(CoreDesignsystemR.string.close))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading || state.filtering || state.busy)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (selected == null) {
                    Text(stringResource(R.string.skills_call_hint))
                    OutlinedTextField(
                        state.query,
                        { onAction(AppAction.QuerySkills(it)) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.skills_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!state.loading && !state.filtering && state.skills.isEmpty())
                        Text(
                            stringResource(
                                if (state.query.isBlank()) R.string.skills_empty
                                else R.string.skills_no_results,
                            ),
                        )
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.skills, key = { it.name }, contentType = { "skill" }) { skill ->
                            OutlinedCard(
                                onClick = { onAction(AppAction.ViewSkill(skill.name)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ListItem(
                                    supportingContent = {
                                        Text(
                                            skill.description,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    overlineContent = {
                                        if (!skill.modelInvocable)
                                            Text(stringResource(R.string.skills_manual))
                                    },
                                ) {
                                    Text(
                                        "/${skill.name}",
                                        fontFamily =
                                            dev.fughetta.dshcompose.ui.appearance
                                                .LocalAppearanceCodeFont
                                                .current,
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                stringResource(R.string.skills_source_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item {
                                Text(
                                    "/${selected.name}",
                                    fontFamily =
                                        dev.fughetta.dshcompose.ui.appearance
                                            .LocalAppearanceCodeFont
                                            .current,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            if (!selected.modelInvocable)
                                item { Text(stringResource(R.string.skills_manual)) }
                            if (selected.description.isNotBlank())
                                item { Text(selected.description) }
                            selected.whenToUse
                                ?.takeIf { it.isNotBlank() }
                                ?.let { usage ->
                                    item { Text(stringResource(R.string.skills_usage, usage)) }
                                }
                            selected.path?.let { path ->
                                item {
                                    Text(
                                        path,
                                        fontFamily =
                                            dev.fughetta.dshcompose.ui.appearance
                                                .LocalAppearanceCodeFont
                                                .current,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            item { Text(stringResource(R.string.skills_draft_hint)) }
                        }
                    }
                    if (state.createdSessionId != null)
                        TextButton(
                            onClick = { onAction(AppAction.OpenSkillCreatedSession) },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.skills_open_created))
                        }
                    val enabled =
                        !state.busy &&
                            !state.loading &&
                            !state.creationUncertain &&
                            state.createdSessionId == null
                    if (state.canUseHere)
                        Button(
                            onClick = { onAction(AppAction.UseSkill(false)) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.skills_use_here))
                        }
                    OutlinedButton(
                        onClick = { onAction(AppAction.UseSkill(true)) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.skills_use_new))
                    }
                }
            }
        }
    }
}
