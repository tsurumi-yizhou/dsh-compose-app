package dev.fughetta.dshcompose.engineui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File

/** Create in Activity.onCreate before STARTED. The controller outlives Activity recreation. */
class EngineActivityBinding(
    private val activity: ComponentActivity,
    private val controller: EngineControlsViewModel,
) : DefaultLifecycleObserver {
    private val save =
        activity.registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            val source = controller.pendingDiagnosticsSave
            controller.pendingDiagnosticsSave = null
            if (uri == null || source == null) controller.exportResult(false)
            else controller.saveDiagnostics(source, uri)
        }

    init {
        val restored =
            activity.savedStateRegistry
                .consumeRestoredStateForKey("engine.diagnostics.save")
                ?.getString("path")
        if (controller.pendingDiagnosticsSave == null && restored != null) {
            val file = File(restored)
            if (file.parentFile == File(activity.cacheDir, "artifacts/diagnostics") && file.isFile)
                controller.pendingDiagnosticsSave = file
        }
        activity.savedStateRegistry.registerSavedStateProvider("engine.diagnostics.save") {
            android.os.Bundle().apply { putString("path", controller.pendingDiagnosticsSave?.path) }
        }
        controller.activity = this
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        controller.onAppResumed()
    }

    override fun onPause(owner: LifecycleOwner) {
        controller.onAppPaused()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        if (controller.activity === this) controller.activity = null
        activity.lifecycle.removeObserver(this)
    }

    internal fun openPage(url: String): Boolean = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        true
    }
        .getOrDefault(false)

    internal fun confirmInstall(intent: Intent): Boolean = runCatching {
        check(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        activity.startActivity(intent)
        true
    }
        .getOrDefault(false)

    internal fun export(file: File, saveCopy: Boolean): Boolean = runCatching {
        if (saveCopy) {
            if (controller.pendingDiagnosticsSave != null) return false
            controller.pendingDiagnosticsSave = file
            save.launch(file.name)
        } else {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val share =
                Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = android.content.ClipData.newRawUri(file.name, uri) }
            activity.startActivity(Intent.createChooser(share, null))
        }
        true
    }
        .getOrElse {
            if (saveCopy) controller.pendingDiagnosticsSave = null
            false
        }
}
