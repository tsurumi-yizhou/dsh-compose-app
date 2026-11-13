package dev.fughetta.dshcompose.transcript

import android.Manifest
import android.content.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.fughetta.dshcompose.ui.transcript.TranscriptArtifactEffect
import java.io.File
import kotlinx.coroutines.flow.collect

@Composable
fun TranscriptArtifactEffects(
    controller: TranscriptArtifactViewModel,
    onPreview: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview by rememberUpdatedState(onPreview)
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            controller.storagePermission(it)
        }
    LaunchedEffect(controller, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.effects.collect { effect ->
                try {
                    when (effect) {
                        is TranscriptArtifactEffect.Preview ->
                            preview(effect.sessionId, effect.path)
                        TranscriptArtifactEffect.StoragePermission ->
                            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        is TranscriptArtifactEffect.CopyPath ->
                            context
                                .getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("", effect.path))
                        is TranscriptArtifactEffect.Open -> {
                            val file = File(effect.path)
                            val uri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.files",
                                    file,
                                )
                            val intent =
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, effect.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .apply { clipData = ClipData.newRawUri(file.name, uri) }
                            context.startActivity(
                                (if (effect.chooser) Intent.createChooser(intent, file.name)
                                    else intent)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        is TranscriptArtifactEffect.Share -> {
                            val file = File(effect.path)
                            val uri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.files",
                                    file,
                                )
                            val send =
                                Intent(Intent.ACTION_SEND)
                                    .setType(effect.mime)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .apply { clipData = ClipData.newRawUri(file.name, uri) }
                            context.startActivity(
                                Intent.createChooser(send, file.name)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                } catch (_: Exception) {
                    controller.platformFailed()
                }
            }
        }
    }
}
