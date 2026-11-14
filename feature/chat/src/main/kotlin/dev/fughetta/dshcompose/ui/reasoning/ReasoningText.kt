package dev.fughetta.dshcompose.ui.reasoning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.markdown.MarkdownMessageText
import kotlinx.coroutines.*

val LocalReasoningTranslation = staticCompositionLocalOf<ReasoningTranslation?> { null }

@Composable
fun ReasoningText(sessionId: String, source: String, running: Boolean) {
    val service = LocalReasoningTranslation.current
    val language = LocalConfiguration.current.locales[0].language
    var limits by remember(service, language) { mutableStateOf<ReasoningTranslationLimits?>(null) }
    LaunchedEffect(service, language) {
        limits =
            try {
                service?.limits()?.takeIf { it.usable && it.maxTextChars >= 2 }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
    }
    val scope = rememberCoroutineScope()
    val holder =
        remember(service, sessionId, language, limits) {
            val accepted = limits
            if (service == null || accepted == null) null
            else
                ReasoningTranslationState(scope) { fragment ->
                    service.translate(sessionId, fragment, accepted, accepted.targetFor(language))
                }
        }
    DisposableEffect(holder) { onDispose { holder?.close() } }
    SideEffect { limits?.let { holder?.update(source, running, it.maxTextChars) } }
    val translation =
        holder?.uiState?.collectAsStateWithLifecycle()?.value ?: ReasoningTranslationUiState()
    var showing by remember(holder) { mutableStateOf(true) }
    var offered by remember { mutableStateOf(false) }
    LaunchedEffect(source, language, limits) {
        offered =
            limits?.let { accepted ->
                withContext(Dispatchers.Default) {
                    reasoningNeedsTranslation(source, accepted.targetFor(language))
                }
            } == true
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (holder != null && (translation.enabled || offered))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (translation.failed) {
                    Text(
                        stringResource(R.string.reasoning_translation_failed),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = holder::retry) {
                        Text(stringResource(R.string.reasoning_translation_retry))
                    }
                } else
                    TextButton(
                        onClick = {
                            if (!translation.enabled) {
                                holder.enable()
                                showing = true
                            } else showing = !showing
                        },
                    ) {
                        Text(
                            stringResource(
                                if (!translation.enabled) R.string.reasoning_translate
                                else if (showing) R.string.reasoning_original
                                else R.string.reasoning_translated,
                            ),
                        )
                    }
                if (translation.pending) CircularProgressIndicator(Modifier.size(18.dp))
            }
        SelectionContainer {
            MarkdownMessageText(
                if (
                    translation.enabled &&
                        showing &&
                        !translation.failed &&
                        translation.text.isNotEmpty()
                )
                    translation.text
                else source,
                ReferenceLabelIndex.Empty,
            )
        }
    }
}
