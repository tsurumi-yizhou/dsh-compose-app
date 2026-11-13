package dev.fughetta.dshcompose.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.transcript.WhaleTail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionSearchScreen(
    state: SessionSearchUiState,
    workspaces: WorkspacesUiState,
    loading: Boolean,
    onAction: (AppAction) -> Unit,
) {
    val connected = workspaces.connected
    val canOpen =
        connected && !workspaces.loading && !workspaces.writing && workspaces.error == null
    LifecycleSearch(onAction)
    var input by remember { mutableStateOf(TextFieldValue(state.input)) }
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    // Search is a type-first page: land in the field with the keyboard ready.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(state.input) {
        if (input.text != state.input)
            input = TextFieldValue(state.input, TextRange(state.input.length))
    }
    // Merged hit order, matching what the list renders so the arrow keys and the list agree.
    val hits: List<SessionSearchHit> = state.results.active + state.results.archived
    val activeCount = state.results.active.size
    val listState = rememberLazyListState()
    var selected by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.query, state.results.active, state.results.archived) { selected = -1 }
    LaunchedEffect(hits.size) { if (selected > hits.lastIndex) selected = hits.lastIndex }
    // Rows the list puts before the first hit: the offline notice and the workspace error.
    val leadingRows = (if (connected) 0 else 1) + (if (workspaces.error != null) 1 else 0)
    fun lazyIndex(hitIndex: Int) =
        if (hitIndex < activeCount) leadingRows + hitIndex
        else leadingRows + activeCount + 1 + (hitIndex - activeCount)
    LaunchedEffect(selected) {
        if (selected >= 0 && selected < hits.size && hits.isNotEmpty()) {
            val index = lazyIndex(selected)
            if (index in 0 until listState.layoutInfo.totalItemsCount)
                listState.animateScrollToItem(index)
        }
    }
    fun openSelected() {
        val hit = hits.getOrNull(selected) ?: hits.firstOrNull() ?: return
        if (canOpen) onAction(AppAction.OpenSearchSession(hit.session.sessionId, state.epoch))
    }
    val navigation = LocalOnBackPressedNavigation()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.session_search)) },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        CenteredContent(
            Modifier.fillMaxSize().padding(padding).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown ->
                        if (hits.isNotEmpty()) {
                            selected = (selected + 1).coerceAtMost(hits.lastIndex)
                            true
                        } else false
                    Key.DirectionUp ->
                        if (hits.isNotEmpty()) {
                            selected = if (selected <= 0) 0 else selected - 1
                            true
                        } else false
                    Key.Enter,
                    Key.NumPadEnter -> {
                        openSelected()
                        true
                    }
                    Key.Escape -> {
                        if (input.text.isNotEmpty()) {
                            input = TextFieldValue()
                            onAction(AppAction.QuerySessions(""))
                        } else navigation()
                        true
                    }
                    else -> false
                }
            },
            maxContentWidth = ContentWidths.Form,
        ) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    input,
                    onValueChange = { value ->
                        input =
                            if (value.text.length <= 200) value
                            else
                                limitSessionSearchInput(value.text).let {
                                    TextFieldValue(it, TextRange(it.length))
                                }
                        onAction(
                            AppAction.QuerySessions(
                                input.text,
                                input.composition?.start,
                                input.composition?.end,
                            ),
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    placeholder = { Text(stringResource(R.string.session_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    trailingIcon = {
                        if (input.text.isNotEmpty())
                            ActionIcon(
                                R.drawable.ic_close,
                                stringResource(R.string.session_search_clear),
                            ) {
                                input = TextFieldValue()
                                onAction(AppAction.QuerySessions(""))
                            }
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .focusRequester(focusRequester)
                            .padding(vertical = Spacing.Small),
                )
                if (state.deriving || loading || workspaces.loading)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                if (hits.isNotEmpty())
                    Text(
                        stringResource(R.string.session_search_keyboard_hint),
                        Modifier.padding(vertical = Spacing.ExtraSmall),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                ) {
                    if (!connected) item { Text(stringResource(R.string.engine_not_connected)) }
                    workspaces.error?.let {
                        item {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { onAction(AppAction.ReloadWorkspaces) }) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    }
                    if (state.query.isEmpty())
                        item { Text(stringResource(R.string.session_search_instructions)) }
                    else {
                        if (
                            state.results.isEmpty &&
                                !state.deriving &&
                                !loading &&
                                state.status != ContentSearchStatus.Pending
                        )
                            item { Text(stringResource(R.string.session_search_empty)) }
                        items(
                            state.results.active,
                            key = { it.session.sessionId },
                            contentType = { "search-hit" },
                        ) { hit ->
                            SearchHitCard(
                                hit,
                                state,
                                canOpen,
                                selected =
                                    hits.getOrNull(selected)?.session?.sessionId ==
                                        hit.session.sessionId,
                                onAction = onAction,
                            )
                        }
                        if (state.results.archived.isNotEmpty())
                            item {
                                Text(
                                    stringResource(R.string.workspace_archived),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        items(
                            state.results.archived,
                            key = { it.session.sessionId },
                            contentType = { "search-hit" },
                        ) { hit ->
                            SearchHitCard(
                                hit,
                                state,
                                canOpen,
                                selected =
                                    hits.getOrNull(selected)?.session?.sessionId ==
                                        hit.session.sessionId,
                                onAction = onAction,
                            )
                        }
                        when (state.status) {
                            ContentSearchStatus.Pending ->
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement =
                                            Arrangement.spacedBy(Spacing.Medium),
                                    ) {
                                        WhaleTail()
                                        Text(stringResource(R.string.session_search_pending))
                                    }
                                }
                            ContentSearchStatus.Unavailable ->
                                item { Text(stringResource(R.string.session_search_titles_only)) }
                            ContentSearchStatus.Failed ->
                                item {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            stringResource(R.string.session_search_failed),
                                            Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = { onAction(AppAction.RetrySessionSearch) },
                                        ) {
                                            Text(stringResource(R.string.refresh))
                                        }
                                    }
                                }
                            else ->
                                if (state.hasMore)
                                    item { Text(stringResource(R.string.session_search_limit)) }
                        }
                    }
                }
            }
        }
    }
}

/** Pops this page through the same dispatcher as the up button, so back stays consistent. */
@Composable
private fun LocalOnBackPressedNavigation(): () -> Unit {
    val dispatcher =
        androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    return { dispatcher?.onBackPressed()?.let {} }
}

@Composable
private fun LifecycleSearch(onAction: (AppAction) -> Unit) {
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        onAction(AppAction.ObserveSessionSearch(true))
        onStopOrDispose { onAction(AppAction.ObserveSessionSearch(false)) }
    }
}

@Composable
private fun SearchHitCard(
    hit: SessionSearchHit,
    state: SessionSearchUiState,
    connected: Boolean,
    selected: Boolean,
    onAction: (AppAction) -> Unit,
) {
    val title = hit.session.title?.ifBlank { null } ?: stringResource(R.string.new_conversation)
    Card(
        onClick = { onAction(AppAction.OpenSearchSession(hit.session.sessionId, state.epoch)) },
        enabled = connected,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors =
            if (selected)
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            SearchHighlightedText(title, state.results.terms, maxLines = 2)
            if (state.results.showFolders)
                sessionFolder(hit.session.cwd)?.let {
                    SearchHighlightedText(it, state.results.terms, maxLines = 1)
                }
            hit.snippet?.takeIf(String::isNotBlank)?.let { snippet ->
                val prepared by
                    produceState<Triple<String, List<String>, SearchExcerpt>?>(
                        null,
                        snippet,
                        state.results.terms,
                    ) {
                        value =
                            withContext(Dispatchers.Default) {
                                Triple(
                                    snippet,
                                    state.results.terms,
                                    sessionSearchExcerpt(snippet, state.results.terms),
                                )
                            }
                    }
                prepared
                    ?.takeIf { it.first == snippet && it.second == state.results.terms }
                    ?.third
                    ?.let { HighlightedSearchRanges(it.text, it.ranges, maxLines = 2) }
            }
            val locale = LocalConfiguration.current.locales[0]
            val date =
                remember(hit.session.updatedAt, locale) {
                    java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT,
                            java.text.DateFormat.SHORT,
                            locale,
                        )
                        .format(java.util.Date(hit.session.updatedAt))
                }
            Text(date, style = MaterialTheme.typography.labelSmall)
            if (hit.session.running)
                Text(
                    stringResource(R.string.session_search_running),
                    style = MaterialTheme.typography.labelSmall,
                )
            if (hit.archived)
                Text(
                    stringResource(R.string.session_search_restore),
                    style = MaterialTheme.typography.labelSmall,
                )
        }
    }
}

@Composable
private fun SearchHighlightedText(text: String, terms: List<String>, maxLines: Int) {
    val prepared by
        produceState<Triple<String, List<String>, List<SearchRange>>?>(null, text, terms) {
            value =
                withContext(Dispatchers.Default) {
                    Triple(text, terms, sessionSearchRanges(text, terms))
                }
        }
    HighlightedSearchRanges(
        text,
        prepared?.takeIf { it.first == text && it.second == terms }?.third.orEmpty(),
        maxLines,
    )
}

@Composable
private fun HighlightedSearchRanges(text: String, ranges: List<SearchRange>, maxLines: Int) {
    val highlight =
        SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    val annotated =
        remember(text, ranges, highlight) {
            buildAnnotatedString {
                var at = 0
                for (range in ranges) {
                    append(text.substring(at, range.start))
                    withStyle(highlight) { append(text.substring(range.start, range.end)) }
                    at = range.end
                }
                append(text.substring(at))
            }
        }
    Text(annotated, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
