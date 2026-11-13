package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.ContextMenu
import dev.fughetta.dshcompose.ui.ImageExportOperation

@Composable
internal fun ReplyImageExportMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    image: ReplyImageRequest,
) {
    val export = LocalReplyImageExport.current
    ContextMenu(expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.image_save)) },
            onClick = {
                onDismiss()
                export(image, ImageExportOperation.Save)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.image_share)) },
            onClick = {
                onDismiss()
                export(image, ImageExportOperation.Share)
            },
        )
    }
}
