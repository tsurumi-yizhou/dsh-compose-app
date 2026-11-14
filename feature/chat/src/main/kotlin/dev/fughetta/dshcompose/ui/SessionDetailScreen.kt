package dev.fughetta.dshcompose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.chat.R

private val groups =
    listOf(
        R.string.session_details_content to FeaturePlacement.SessionContent,
        R.string.session_details_tools to FeaturePlacement.SessionTools,
        R.string.session_details_manage to FeaturePlacement.SessionManage,
    )

/**
 * 对话详情: the things to see and do about one conversation, as a page on a phone and as the right-hand
 * pane next to the chat on a wide window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    id: String,
    contributions: FeatureContributions,
    syncing: Boolean,
    besideChat: Boolean,
    onClose: () -> Unit,
    onAction: (AppAction) -> Unit,
    onPage: (FeaturePage) -> Unit,
) {
    // Close explicitly: beside the chat the list-detail scene may leave back unhandled, and the
    // activity's fallback sends the whole app to the background.
    BackHandler(onBack = onClose)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.session_details)) },
                navigationIcon = {
                    if (besideChat)
                        ActionIcon(
                            CoreDesignsystemR.drawable.ic_close,
                            stringResource(CoreDesignsystemR.string.close),
                            onClick = onClose,
                        )
                    else UpButton(onClose)
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            groups.forEachIndexed { index, (title, placement) ->
                if (index > 0) item { HorizontalDivider() }
                item {
                    Text(
                        stringResource(title),
                        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(contributions.at(placement), key = { it.id }) { entry ->
                    val target = entry.target(id)
                    ListItem(
                        modifier =
                            Modifier.clickable(enabled = !syncing && target != null) {
                                target?.open(onAction, onPage)
                            },
                    ) {
                        Text(stringResource(entry.label))
                    }
                }
            }
        }
    }
}
