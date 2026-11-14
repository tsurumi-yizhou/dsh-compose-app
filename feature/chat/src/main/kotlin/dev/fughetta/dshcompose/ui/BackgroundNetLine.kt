package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*

/** One quiet line under the chat header about background running; a tap puts it away. */
@Composable
internal fun ColumnScope.BackgroundNetLine(
    reason: dev.fughetta.dshcompose.ui.runtime.BackgroundNetReason,
    onAction: (AppAction) -> Unit,
) {
    Surface(
        onClick = { onAction(AppAction.BackgroundNet(open = false)) },
        modifier =
            Modifier.align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    if (reason == dev.fughetta.dshcompose.ui.runtime.BackgroundNetReason.Intro)
                        R.string.background_net_finish
                    else R.string.background_net_restricted,
                ),
                Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = { onAction(AppAction.BackgroundNet(open = true)) }) {
                Text(stringResource(R.string.background_net_allow))
            }
        }
    }
}
