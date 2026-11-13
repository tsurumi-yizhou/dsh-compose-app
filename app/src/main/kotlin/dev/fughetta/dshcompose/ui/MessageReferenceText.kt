package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.composer.ReferenceDisplay
import dev.fughetta.dshcompose.ui.composer.parseMessageReferences
import dev.fughetta.dshcompose.ui.composer.referenceDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun MessageReferenceText(text: String, labels: ReferenceLabelIndex) {
    // LazyColumn owns this work: rows leaving the viewport cancel it and release their result.
    var parsed by remember(text, labels) { mutableStateOf<ReferenceDisplay?>(null) }
    LaunchedEffect(text, labels) {
        if ('@' in text) {
            // A streaming message can change every frame. Parse after a brief pause in its text.
            delay(100)
            parsed =
                withContext(Dispatchers.Default) {
                    referenceDisplay(text, parseMessageReferences(text, labels))
                }
        }
    }
    val display = parsed
    val colors = MaterialTheme.colorScheme
    val annotated =
        remember(display, colors.secondaryContainer, colors.onSecondaryContainer) {
            display?.let {
                AnnotatedString.Builder(it.text)
                    .apply {
                        it.spans.forEach { span ->
                            addStyle(
                                SpanStyle(
                                    background = colors.secondaryContainer,
                                    color = colors.onSecondaryContainer,
                                    fontWeight = FontWeight.Medium,
                                ),
                                span.start,
                                span.end,
                            )
                        }
                    }
                    .toAnnotatedString()
            }
        }
    val raw = remember(text) { AnnotatedString(text) }
    SelectionContainer { Text(annotated ?: raw, style = MaterialTheme.typography.bodyLarge) }
}
