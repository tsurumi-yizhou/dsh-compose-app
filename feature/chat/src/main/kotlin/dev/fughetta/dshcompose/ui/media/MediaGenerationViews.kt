package dev.fughetta.dshcompose.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.GeneratedMediaItem
import dev.fughetta.dshcompose.domain.media.MediaKind
import dev.fughetta.dshcompose.domain.media.MediaParameters
import dev.fughetta.dshcompose.domain.media.MediaStatus
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.ImageExportOperation
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.EmptyStatePanel
import dev.fughetta.dshcompose.ui.adaptive.LazyAdaptiveGrid
import dev.fughetta.dshcompose.ui.adaptive.ProblemPanel
import dev.fughetta.dshcompose.ui.adaptive.WindowTier
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import dev.fughetta.dshcompose.ui.markdown.*
import kotlinx.serialization.json.*

@Composable
fun GenerationControls(
    state: MediaGenerationState,
    draftKey: String,
    onAction: (MediaAction) -> Unit,
) {
    if (state.catalog.status == MediaCatalogStatus.Missing) return
    val params = state.modes[draftKey] ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = {
                if (state.catalog.entries.isEmpty()) onAction(MediaAction.Settings)
                else onAction(MediaAction.Parameters(draftKey))
            },
            label = { Text(params.model?.label ?: stringResource(R.string.media_generation)) },
        )
        TextButton(onClick = { onAction(MediaAction.Mode(draftKey, null)) }) {
            Text(stringResource(R.string.media_exit_generation))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationParameters(state: MediaGenerationState, onAction: (MediaAction) -> Unit) {
    val key = state.parameterKey ?: return
    val params = state.modes[key] ?: return
    val entry = state.catalog.resolve(params.model, params.kind)
    fun change(next: MediaParameters) = onAction(MediaAction.Mode(key, next))
    ModalBottomSheet(onDismissRequest = { onAction(MediaAction.CloseParameters) }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            Text(
                stringResource(R.string.media_generation),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                MediaKind.entries.forEach { kind ->
                    FilterChip(
                        params.kind == kind,
                        { change(state.catalog.fit(MediaParameters(kind), start = true)) },
                        enabled = state.catalog.entries.any { it.model.kind == kind },
                        label = { Text(kindLabel(kind)) },
                    )
                }
            }
            Text(stringResource(R.string.media_model), style = MaterialTheme.typography.titleSmall)
            Column(
                Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                state.catalog.entries
                    .filter { it.model.kind == params.kind }
                    .forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = option.ref == params.model,
                                    role = Role.RadioButton,
                                    onClick = { change(params.copy(model = option.ref)) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.ref == params.model, onClick = null)
                            Column {
                                Text(option.model.label)
                                Text(
                                    option.service.title,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
            }
            if (entry == null)
                Text(
                    stringResource(R.string.media_model_unavailable),
                    color = MaterialTheme.colorScheme.error,
                )
            entry?.model?.let { model ->
                ChoiceRow(stringResource(R.string.media_aspect), model.ratios, params.aspect) {
                    change(params.copy(aspect = it))
                }
                if (params.kind == MediaKind.Image) {
                    ChoiceRow(
                        stringResource(R.string.media_count),
                        (1..model.maxN).map(Int::toString),
                        params.count.toString(),
                    ) {
                        change(params.copy(count = it?.toIntOrNull() ?: 1))
                    }
                    ChoiceRow(
                        stringResource(R.string.media_quality),
                        model.qualities,
                        params.quality,
                        automatic = true,
                    ) {
                        change(params.copy(quality = it))
                    }
                } else {
                    ChoiceRow(
                        stringResource(R.string.media_duration),
                        model.durations.map(Int::toString),
                        params.seconds?.toString(),
                    ) {
                        change(params.copy(seconds = it?.toIntOrNull()))
                    }
                    ChoiceRow(
                        stringResource(R.string.media_resolution),
                        model.resolutions,
                        params.resolution,
                    ) {
                        change(params.copy(resolution = it))
                    }
                }
                model.price?.let { price ->
                    val amount = price.amount
                    val units =
                        if (price.unit == "second") params.seconds
                        else if (params.kind == MediaKind.Image) params.count else 1
                    if (amount != null && amount.isFinite() && amount >= 0 && units != null) {
                        val total =
                            java.math.BigDecimal.valueOf(amount * units)
                                .setScale(4, java.math.RoundingMode.HALF_UP)
                                .stripTrailingZeros()
                                .toPlainString()
                        Text(
                            stringResource(
                                R.string.media_estimate,
                                "$total ${price.currency ?: "CNY"}",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            TextButton(
                onClick = {
                    onAction(MediaAction.CloseParameters)
                    onAction(MediaAction.Settings)
                },
            ) {
                Text(stringResource(R.string.media_settings))
            }
            Button(
                onClick = { onAction(MediaAction.CloseParameters) },
                modifier = Modifier.fillMaxWidth(),
                enabled = entry != null,
            ) {
                Text(stringResource(R.string.media_done))
            }
        }
    }
}

@Composable
fun GeneratedMediaCard(
    media: GeneratedMedia,
    sessionId: String,
    createdAt: Long,
    onAction: (MediaAction) -> Unit,
    progress: String? = null,
) {
    val work =
        remember(media, sessionId, createdAt) {
            MediaWork(media.rid ?: "tool:$createdAt", sessionId, createdAt, null, media, true)
        }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Text(
                media.request.label ?: media.request.model ?: kindLabel(media.kind),
                style = MaterialTheme.typography.titleSmall,
            )
            when (media.status) {
                MediaStatus.Running -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress ?: stringResource(R.string.media_generating))
                    media.job?.let { job ->
                        TextButton(onClick = { onAction(MediaAction.CancelJob(sessionId, job)) }) {
                            Text(stringResource(R.string.media_stop))
                        }
                    }
                }
                MediaStatus.Failed ->
                    Text(
                        media.error?.text
                            ?: media.error?.code
                            ?: stringResource(R.string.media_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                MediaStatus.Done -> Unit
            }
            media.items.chunked(2).forEachIndexed { row, items ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
                    items.forEachIndexed { column, item ->
                        Box(
                            Modifier.weight(1f).clickable {
                                onAction(MediaAction.Preview(work, row * 2 + column))
                            },
                        ) {
                            GeneratedMediaThumbnail(work, item)
                        }
                    }
                }
            }
            MediaInformation(media)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { onAction(MediaAction.Reuse(work, send = true)) }) {
                    Text(stringResource(R.string.media_regenerate))
                }
                TextButton(onClick = { onAction(MediaAction.Reuse(work)) }) {
                    Text(stringResource(R.string.media_revise))
                }
                if (media.copyPrompt != null)
                    TextButton(onClick = { onAction(MediaAction.CopyPrompt(work)) }) {
                        Text(stringResource(R.string.media_copy_prompt))
                    }
            }
        }
    }
}

@Composable
private fun GeneratedMediaThumbnail(work: MediaWork, item: GeneratedMediaItem) {
    val images = LocalReplyImages.current
    val address = if (work.media.kind == MediaKind.Video) item.poster else item.path
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        if (address != null && images.loader != null)
            CompositionLocalProvider(
                LocalReplyImages provides images.copy(sessionId = work.sessionId),
            ) {
                AsyncImage(
                    rememberReplyImageRequest(address, 512, 0),
                    item.name,
                    images.loader,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        else Text(kindLabel(work.media.kind))
        if (work.media.kind == MediaKind.Video)
            Text(
                "▶ ${item.duration?.toInt()?.let { "${it}s" }.orEmpty()}",
                Modifier.align(Alignment.BottomEnd).padding(Spacing.Small),
            )
    }
}

/** One gallery cell: the existing click-to-preview tile, now shared by every column count. */
@Composable
private fun WorkTile(work: MediaWork, onAction: (MediaAction) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onAction(MediaAction.Preview(work)) }) {
        work.media.items.firstOrNull()?.let { GeneratedMediaThumbnail(work, it) }
        Text(
            work.media.request.label ?: kindLabel(work.media.kind),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaWorksScreen(state: MediaGenerationState, onAction: (MediaAction) -> Unit) {
    val works = state.works
    val tier = windowTier()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.media_works)) },
                    navigationIcon = { UpButton() },
                )
                if (works.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        // The gallery keeps the full width: its adaptive grid adds columns instead of stretching.
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.Large, vertical = Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                FilterChip(
                    works.filter == null,
                    { onAction(MediaAction.Works()) },
                    label = { Text(stringResource(R.string.media_all)) },
                )
                MediaKind.entries.forEach { kind ->
                    FilterChip(
                        works.filter == kind,
                        { onAction(MediaAction.Works(kind)) },
                        label = { Text(kindLabel(kind)) },
                    )
                }
            }
            if (works.local)
                Text(
                    stringResource(R.string.media_local_works),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            works.error?.let { code ->
                ProblemPanel(
                    title = stringResource(R.string.media_error_title),
                    message = stringResource(R.string.media_error_hint),
                    code = code,
                    primaryLabel = stringResource(R.string.media_retry),
                    onPrimary = { onAction(MediaAction.Works(works.filter)) },
                )
            }
            if (works.items.isEmpty() && !works.loading && works.error == null)
                EmptyStatePanel(
                    title = stringResource(R.string.media_no_works),
                    body = stringResource(R.string.media_no_works_hint),
                )
            LazyAdaptiveGrid(
                minItemWidth = if (tier.atLeastExpanded) 240.dp else 112.dp,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                spacing = if (tier.atLeastExpanded) Spacing.Medium else Spacing.ExtraSmall,
                maxColumns =
                    when {
                        tier >= WindowTier.ExtraLarge -> 5
                        tier >= WindowTier.Large -> 4
                        tier >= WindowTier.Expanded -> 3
                        else -> 6
                    },
            ) {
                items(works.items, key = { it.key }) { work -> WorkTile(work, onAction) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { onAction(MediaAction.PreviousWorks) },
                    enabled = works.hasPrevious && !works.loading,
                ) {
                    Text(stringResource(R.string.media_previous))
                }
                TextButton(
                    onClick = { onAction(MediaAction.MoreWorks) },
                    enabled = works.next != null && !works.loading,
                ) {
                    Text(stringResource(R.string.media_next))
                }
            }
        }
    }
}

@Composable
fun GeneratedMediaViewer(state: MediaGenerationState, onAction: (MediaAction) -> Unit) {
    val preview = state.preview ?: return
    val work = preview.works.getOrNull(preview.workIndex) ?: return
    val item = work.media.items.getOrNull(preview.itemIndex) ?: return
    val images = LocalReplyImages.current
    Dialog(
        onDismissRequest = { onAction(MediaAction.ClosePreview) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(Spacing.Medium)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { onAction(MediaAction.ClosePreview) }) {
                        Text(stringResource(R.string.media_close))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (work.media.kind == MediaKind.Video) {
                        if (preview.downloading) {
                            val progress = preview.progress
                            if (progress == null) CircularProgressIndicator()
                            else CircularProgressIndicator(progress = { progress })
                        }
                        preview.videoPath?.let { GeneratedVideoPlayer(it) }
                    } else if (images.loader != null)
                        CompositionLocalProvider(
                            LocalReplyImages provides images.copy(sessionId = work.sessionId),
                        ) {
                            GeneratedImagePreview(
                                rememberReplyImageRequest(item.path, 4096, 0),
                                item.name,
                                images.loader,
                            )
                        }
                    preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                MediaInformation(work.media)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            onAction(MediaAction.Export(work, item, ImageExportOperation.Save))
                        },
                    ) {
                        Text(stringResource(R.string.media_save))
                    }
                    TextButton(
                        onClick = {
                            onAction(MediaAction.Export(work, item, ImageExportOperation.Share))
                        },
                    ) {
                        Text(stringResource(R.string.media_share))
                    }
                    if (work.media.kind == MediaKind.Image) {
                        TextButton(
                            onClick = { onAction(MediaAction.Reuse(work, reference = item)) },
                        ) {
                            Text(stringResource(R.string.media_edit))
                        }
                        TextButton(
                            onClick = {
                                onAction(MediaAction.Reuse(work, reference = item, video = true))
                            },
                        ) {
                            Text(stringResource(R.string.media_make_video))
                        }
                    }
                    TextButton(onClick = { onAction(MediaAction.GoConversation(work.sessionId)) }) {
                        Text(stringResource(R.string.media_conversation))
                    }
                    if (!work.local)
                        TextButton(onClick = { onAction(MediaAction.Delete(work)) }) {
                            Text(stringResource(R.string.media_delete))
                        }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val before =
                        if (preview.itemIndex > 0) preview.workIndex to preview.itemIndex - 1
                        else
                            (preview.workIndex - 1)
                                .takeIf { it >= 0 }
                                ?.let { it to preview.works[it].media.items.lastIndex }
                    val after =
                        if (preview.itemIndex < work.media.items.lastIndex)
                            preview.workIndex to preview.itemIndex + 1
                        else
                            (preview.workIndex + 1)
                                .takeIf { it < preview.works.size }
                                ?.let { it to 0 }
                    TextButton(
                        onClick = {
                            before?.let {
                                onAction(MediaAction.PreviewPosition(it.first, it.second))
                            }
                        },
                        enabled = before != null,
                    ) {
                        Text(stringResource(R.string.media_previous))
                    }
                    TextButton(
                        onClick = {
                            after?.let {
                                onAction(MediaAction.PreviewPosition(it.first, it.second))
                            }
                        },
                        enabled = after != null,
                    ) {
                        Text(stringResource(R.string.media_next))
                    }
                }
            }
        }
    }
}

@Composable
fun MediaDeleteConfirmation(state: MediaGenerationState, onAction: (MediaAction) -> Unit) {
    val work = state.deleteConfirmation ?: return
    AlertDialog(
        onDismissRequest = { onAction(MediaAction.Delete(null)) },
        title = { Text(stringResource(R.string.media_delete_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.media_delete_scope))
                work.media.paths.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(MediaAction.ConfirmDelete) }, enabled = !state.busy) {
                Text(stringResource(R.string.media_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(MediaAction.Delete(null)) }, enabled = !state.busy) {
                Text(stringResource(R.string.media_cancel))
            }
        },
    )
}

@Composable
private fun MediaInformation(media: GeneratedMedia) {
    if (media.status == MediaStatus.Done)
        Text(
            stringResource(R.string.media_ai_generated),
            style = MaterialTheme.typography.labelSmall,
        )
    val request = media.request
    val parts =
        listOfNotNull(
            request.aspect,
            request.quality,
            request.resolution,
            request.secondsLabel?.let { "${it}s" },
        )
    if (parts.isNotEmpty())
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
    media.cost?.let { cost ->
        cost.estimate
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let {
                Text(
                    stringResource(
                        R.string.media_estimate,
                        "$it ${cost.currency ?: "CNY"}",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
    }
    media.provenance?.let { provenance ->
        if (provenance.visibleMark || provenance.c2pa || provenance.implicitLabel)
            Text(
                stringResource(R.string.media_provenance),
                style = MaterialTheme.typography.labelSmall,
            )
    }
}

@Composable
internal fun kindLabel(kind: MediaKind) =
    stringResource(if (kind == MediaKind.Image) R.string.media_image else R.string.media_video)
