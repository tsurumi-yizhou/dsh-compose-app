package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.domain.sessions.TranscriptTurnTrace
import dev.fughetta.dshcompose.feature.chat.R
import kotlinx.coroutines.*

@Composable
fun TranscriptActivityStrip(
    trace: TranscriptTurnTrace,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var now by remember(trace.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleStartEffect(trace.id, trace.end) {
        val ticker = scope.launch {
            while (isActive && trace.end == null) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        onStopOrDispose { ticker.cancel() }
    }
    val active = remember(trace) { trace.spans.lastOrNull { it.end == null } }
    TextButton(onOpen, modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        if (trace.end == null) WhaleTail()
        Spacer(Modifier.width(8.dp))
        Text(
            active?.label
                ?: stringResource(
                    if (active?.kind == "approval" || active?.kind == "question")
                        R.string.tool_approval
                    else R.string.thinking,
                ),
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            transcriptSpanTime((trace.end ?: now) - trace.start),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
