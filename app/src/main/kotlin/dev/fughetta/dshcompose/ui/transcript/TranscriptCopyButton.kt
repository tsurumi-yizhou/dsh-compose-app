package dev.fughetta.dshcompose.ui.transcript

import android.content.ClipData
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.R
import kotlinx.coroutines.launch

@Composable
fun TranscriptCopyButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextButton(
        { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) } },
    ) {
        Text(stringResource(R.string.transcript_copy))
    }
}
