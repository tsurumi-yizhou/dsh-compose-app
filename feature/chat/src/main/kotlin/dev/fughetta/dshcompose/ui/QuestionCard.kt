package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.domain.messages.QuestionAnswer
import dev.fughetta.dshcompose.feature.chat.R

@Composable
internal fun QuestionCard(
    sessionId: String,
    syncing: Boolean,
    state: QuestionUiState,
    onAction: (AppAction) -> Unit,
) {
    val enabled = !syncing && !state.responding
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.remainingMs?.let {
                Text(
                    stringResource(
                        R.string.question_remaining,
                        ((it.coerceAtLeast(0) + 999) / 1000),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            for (question in state.request.questions) {
                val answer = state.answers[question.id] ?: QuestionAnswer(question.id)
                question.header?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                Text(question.question, style = MaterialTheme.typography.titleMedium)
                question.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                for (option in question.options) {
                    FilterChip(
                        selected = option.label in answer.selected,
                        enabled = enabled,
                        onClick = {
                            val selected =
                                if (question.multiSelect) {
                                    if (option.label in answer.selected)
                                        answer.selected - option.label
                                    else answer.selected + option.label
                                } else listOf(option.label)
                            onAction(
                                AppAction.EditQuestion(
                                    sessionId,
                                    state,
                                    answer.copy(
                                        selected = selected,
                                        custom = answer.custom.takeIf { question.multiSelect },
                                    ),
                                ),
                            )
                        },
                        label = {
                            Column {
                                Text(option.label)
                                option.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = answer.custom.orEmpty(),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.question_custom_answer)) },
                    onValueChange = { text ->
                        onAction(
                            AppAction.EditQuestion(
                                sessionId,
                                state,
                                answer.copy(
                                    selected =
                                        answer.selected
                                            .takeIf { question.multiSelect || text.isBlank() }
                                            .orEmpty(),
                                    custom = text,
                                ),
                            ),
                        )
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        onAction(AppAction.RespondQuestion(sessionId, state, reject = true))
                    },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.reject))
                }
                Button(
                    onClick = { onAction(AppAction.RespondQuestion(sessionId, state)) },
                    enabled =
                        enabled &&
                            state.request.questions.all {
                                val answer = state.answers[it.id]
                                answer != null &&
                                    (answer.selected.isNotEmpty() || !answer.custom.isNullOrBlank())
                            },
                ) {
                    Text(stringResource(R.string.question_submit))
                }
            }
        }
    }
}
