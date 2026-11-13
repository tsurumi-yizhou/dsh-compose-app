package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.TranscriptTurnTrace
import dev.fughetta.dshcompose.domain.transcript.TranscriptSegment
import dev.fughetta.dshcompose.domain.usage.formatUsageTokens
import dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont

fun transcriptSpanTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000.0
    return if (seconds < 60) String.format(java.util.Locale.ROOT, "%.1fs", seconds)
    else "${seconds.toInt()/60}m ${seconds.toInt()%60}s"
}

@Composable
fun TranscriptProcessRow(
    segment: TranscriptSegment,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onToggle,
        modifier,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(
            buildList {
                    add(stringResource(R.string.transcript_completed))
                    if (segment.tools > 0)
                        add("${segment.tools} ${stringResource(R.string.transcript_tools)}")
                    if (segment.messages > 1)
                        add("${segment.messages-1} ${stringResource(R.string.transcript_messages)}")
                    if (segment.tools == 0 && segment.messages <= 1 && segment.thoughts > 0)
                        add("${segment.thoughts} ${stringResource(R.string.transcript_thoughts)}")
                }
                .joinToString(" · "),
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            null,
            Modifier.padding(start = 4.dp).size(18.dp).rotate(if (segment.folded) 90f else 270f),
        )
    }
}

@Composable
fun TranscriptFootnote(
    trace: TranscriptTurnTrace,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val parts = buildList {
        add(
            trace.duration?.let(::transcriptSpanTime)
                ?: stringResource(R.string.transcript_running),
        )
        if (trace.steps > 0) add("${trace.steps} ${stringResource(R.string.transcript_steps)}")
        if (trace.usage.requests > 0) add("${formatUsageTokens(trace.usage.total)} tokens")
        if (!note.isNullOrBlank()) add(note)
    }
    TextButton(
        onOpen,
        modifier,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptTrajectorySheet(trace: TranscriptTurnTrace, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.transcript_trajectory),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    trace.duration?.let(::transcriptSpanTime)
                        ?: stringResource(R.string.transcript_running),
                )
            }
            if (trace.usage.requests > 0)
                item {
                    Text(
                        "${stringResource(R.string.transcript_input)} ${formatUsageTokens(trace.usage.input+trace.usage.cacheRead+trace.usage.cacheWrite)} · ${stringResource(R.string.transcript_output)} ${formatUsageTokens(trace.usage.output)}",
                        fontFamily = LocalAppearanceCodeFont.current,
                    )
                }
            items(trace.spans.size) { index ->
                val span = trace.spans[index]
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row {
                            Text(
                                span.label ?: span.model ?: span.kind,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                span.duration?.let(::transcriptSpanTime)
                                    ?: stringResource(R.string.transcript_running),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        span.model?.let {
                            Text(
                                listOfNotNull(span.provider, it).joinToString(" / "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (span.retries > 0)
                            Text("${stringResource(R.string.transcript_retry)} · ${span.retries}")
                        span.ttft?.let {
                            Text(
                                "${stringResource(R.string.transcript_first_token)} · ${transcriptSpanTime(it)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        span.tokensPerSecond?.let {
                            Text(
                                String.format(java.util.Locale.ROOT, "%.1f tokens/s", it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (span.usage.requests > 0)
                            Text(
                                "${formatUsageTokens(span.usage.total)} tokens",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        span.outcome?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        if (span.failed)
                            Text(
                                stringResource(R.string.tool_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        span.arguments?.takeIf { it.isNotBlank() }?.let { TranscriptOutput(it) }
                    }
                }
            }
        }
    }
}
