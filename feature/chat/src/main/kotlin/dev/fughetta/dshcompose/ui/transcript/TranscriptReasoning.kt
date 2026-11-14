package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.phone.performanceContentSize
import dev.fughetta.dshcompose.ui.reasoning.ReasoningText

/**
 * The closed live window touches only a bounded tail; full rendering/translation mounts on demand.
 */
@Composable
fun TranscriptReasoning(
    sessionId: String,
    rowKey: String,
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    var expanded by rememberSaveable(sessionId, rowKey) { mutableStateOf(false) }
    val tail =
        remember(text) {
            text.takeLast(2400).lineSequence().toList().takeLast(3).joinToString("\n")
        }
    Column(
        modifier
            .fillMaxWidth()
            .performanceContentSize(
                dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings.current,
            ),
    ) {
        TextButton({ expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.thinking))
            Icon(
                painterResource(CoreDesignsystemR.drawable.ic_chevron_right),
                null,
                Modifier.padding(start = 4.dp).size(18.dp).rotate(if (expanded) 270f else 90f),
            )
        }
        if (expanded) ReasoningText(sessionId, text, streaming)
        else if (streaming)
            Text(
                tail,
                Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 60.dp),
                maxLines = 3,
                overflow = TextOverflow.Clip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
