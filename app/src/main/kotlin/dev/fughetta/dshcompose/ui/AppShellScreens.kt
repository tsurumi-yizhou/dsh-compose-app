package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LicensesScreen(state: AppState) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.open_source_licenses)) },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        when {
            state.licensesError != null ->
                Box(Modifier.padding(padding)) { ErrorCard(state.licensesError) }
            state.licenseDocuments == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            else ->
                ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(inset, 16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        items(state.licenseDocuments, key = { it.path }) { document ->
                            if (document.path.endsWith(".md")) {
                                dev.fughetta.dshcompose.ui.markdown.MarkdownMessageText(
                                    document.text,
                                    ReferenceLabelIndex.Empty,
                                    copySource = false,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    HorizontalDivider()
                                    Text(
                                        stringResource(
                                            if (document.path == "licenses/LICENSE")
                                                R.string.license_project
                                            else R.string.license_font,
                                        ),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    SelectionContainer {
                                        Text(
                                            document.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
internal fun ErrorCard(message: String, modifier: Modifier = Modifier.padding(12.dp)) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

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
