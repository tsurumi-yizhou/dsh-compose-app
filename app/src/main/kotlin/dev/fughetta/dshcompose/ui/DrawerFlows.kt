package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.coroutines.delay

/** The eight identity hues a folder can wear, indexed by [DrawerFolder.tint]. */
internal val FolderTints =
    listOf(
        Color(0xFFE53935),
        Color(0xFFFB8C00),
        Color(0xFFFDD835),
        Color(0xFF43A047),
        Color(0xFF00ACC1),
        Color(0xFF1E88E5),
        Color(0xFF8E24AA),
        Color(0xFFD81B60),
    )

internal val FolderEmoji = listOf("📁", "💼", "🧪", "📚", "🛠️", "💡", "🎯", "⭐", "🎨")

/** The mark in front of a folder's name: plain, a hue or an emoji. */
@Composable
internal fun FolderMark(tint: Int?, emoji: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when {
            emoji != null -> Text(emoji)
            tint != null ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(FolderTints[tint % 8]))
            else ->
                Box(
                    Modifier.size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline),
                )
        }
    }
}

/** 新建文件夹 / 编辑文件夹: a name and a mark. [taken] names can't be reused. */
@Composable
internal fun FolderEditorDialog(
    title: Int,
    initial: DrawerFolder?,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, tint: Int?, emoji: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var tint by remember { mutableStateOf(initial?.tint) }
    var emoji by remember { mutableStateOf(initial?.emoji) }
    val clean = cleanFolderName(name)
    val duplicate = clean.isNotEmpty() && clean in taken && clean != initial?.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = {
                        if (duplicate) Text(stringResource(R.string.drawer_folder_taken))
                    },
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MarkChoice(tint == null && emoji == null) {
                        tint = null
                        emoji = null
                    }
                    for (i in FolderTints.indices) MarkChoice(emoji == null && tint == i, i) {
                        tint = i
                        emoji = null
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (e in FolderEmoji) MarkChoice(emoji == e, emoji = e) {
                        emoji = e
                        tint = null
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = clean.isNotEmpty() && !duplicate,
                onClick = {
                    onSave(clean, if (emoji == null) tint else null, emoji)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MarkChoice(
    selected: Boolean,
    tint: Int? = null,
    emoji: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { FolderMark(tint, emoji) }
    }
}

/** 管理文件夹: reorder, edit and delete in one place. */
@Composable
internal fun ManageFoldersDialog(
    folders: List<DrawerFolder>,
    onAction: (DrawerAction) -> Unit,
    onEdit: (DrawerFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_folders_manage)) },
        text = {
            Column {
                if (folders.isEmpty()) Text(stringResource(R.string.drawer_folders_none))
                folders.forEachIndexed { index, folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FolderMark(folder.tint, folder.emoji)
                        Text(
                            folder.name,
                            Modifier.weight(1f).clickable { onEdit(folder) }.padding(8.dp),
                            maxLines = 1,
                        )
                        TextButton(
                            enabled = index > 0,
                            onClick = { onAction(DrawerAction.ReorderFolder(index, index - 1)) },
                        ) {
                            Text("↑")
                        }
                        TextButton(
                            enabled = index < folders.lastIndex,
                            onClick = { onAction(DrawerAction.ReorderFolder(index, index + 1)) },
                        ) {
                            Text("↓")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        },
    )
}

/**
 * A change that lands after an undo window ("已归档 3 项 撤销"). [onCommit] runs when the window ends or
 * the list goes away first; [onUndo] cancels it.
 */
internal class DrawerNotice(
    val text: Int,
    val arg: Int? = null,
    val onUndo: () -> Unit,
    val onCommit: () -> Unit,
)

@Composable
internal fun DrawerNoticeBar(notice: DrawerNotice, onDone: () -> Unit) {
    val commit by rememberUpdatedState(notice.onCommit)
    var settled by remember(notice) { mutableStateOf(false) }
    LaunchedEffect(notice) {
        delay(5_000)
        settled = true
        commit()
        onDone()
    }
    DisposableEffect(notice) { onDispose { if (!settled) commit() } }
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (notice.arg == null) stringResource(notice.text)
            else stringResource(notice.text, notice.arg),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                settled = true
                notice.onUndo()
                onDone()
            },
        ) {
            Text(stringResource(R.string.drawer_undo))
        }
    }
}
