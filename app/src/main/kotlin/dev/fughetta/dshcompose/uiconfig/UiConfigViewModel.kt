package dev.fughetta.dshcompose.uiconfig

// Follows the active host's `$DSH_HOME/dsh-compose/ui.json` and publishes what it applies. On the
// phone's own engine the file is read straight from the rootfs and watched with inotify; on a
// remote host it is read through `workspaceFiles/read` and followed with `workspaceFiles/changes`,
// falling back to a check every minute while the app is in front. Both only read ui.json; the local
// one keeps `ui.status.json` next to it (what the app applied) for the agent to check its work.
//
// The last good text is remembered per host, so a cold start draws the same look before the file
// is read again; a fatal read keeps the previous good configuration.
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import dev.fughetta.dshcompose.domain.uiconfig.UiConfigPorts
import dev.fughetta.dshcompose.domain.uiconfig.UiConfigRead
import dev.fughetta.dshcompose.ui.toUiPayload
import dev.fughetta.dshcompose.ui.uiconfig.UiConfig
import dev.fughetta.dshcompose.ui.uiconfig.UiConfigLoad
import dev.fughetta.dshcompose.ui.uiconfig.UiMessage
import dev.fughetta.dshcompose.ui.uiconfig.UiSchema
import dev.fughetta.dshcompose.ui.uiconfig.parseUiConfig
import dev.fughetta.dshcompose.ui.uiconfig.render
import dev.fughetta.dshcompose.ui.uiconfig.uiConfigBroken
import dev.fughetta.dshcompose.ui.uiconfig.uiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class UiConfigViewModel(
    private val app: Application,
    parent: CoroutineScope? = null,
    settings: (maxBytes: () -> Int) -> SettingsRepository,
    private val publish: (UiConfig?) -> Unit,
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private val source = settings { schema.maxBytes }
    private var epoch = 0L

    override fun onCleared() {
        close()
    }

    private val schema by lazy {
        UiSchema.parse(
            app.assets.open("skills/dsh-compose-ui/schema.json").use { it.reader().readText() },
        )
    }
    private var host: String? = null
    private var owner: UiConfigPorts? = null
    private var job: Job? = null
    private var kicks = Channel<Unit>(Channel.CONFLATED)
    private var load: UiConfigLoad? = null
    private var lastText: String? = null

    /** Follows [host]'s file; a remote host needs its open [client]. */
    fun connect(client: UiConfigPorts?, host: String) {
        if (this.host == host && owner === client) return
        val own = ++epoch
        owner = client
        job?.cancel()
        job = null
        kicks.close()
        if (host != this.host) {
            this.host = host
            lastText = null
            source.resetUiConfigStatus()
            load = null
            publish(null)
            scope.launch(Dispatchers.IO) { restore(host) }
        }
        if (host != "local" && client == null) return
        val kicks = Channel<Unit>(Channel.CONFLATED).also { kicks = it }
        job =
            scope.launch(Dispatchers.IO) {
                val receive: suspend (UiConfigRead, String?) -> Unit = { read, sink ->
                    onRead(host, own, read, sink)
                }
                source.followUiConfig(host == "local", client, kicks.receiveAsFlow(), receive)
            }
    }

    /** Look again: the app came to the front, the network or the runtime changed. */
    fun kick() {
        kicks.trySend(Unit)
    }

    fun close() {
        epoch++
        job?.cancel()
        job = null
        kicks.close()
        owner = null
        host = null
    }

    /** A conversation may have appeared on a host whose file could not be located yet. */
    fun hostEvent(name: String?) {
        if (source.uiConfigUnresolved && name == "api-session/added") kick()
    }

    private suspend fun restore(host: String) {
        val text = source.restoreUiConfig(host) ?: return
        val restored = runCatching { parseUiConfig(text, schema) }.getOrNull() ?: return
        withContext(Dispatchers.Main) {
            if (this@UiConfigViewModel.host == host && load == null && !restored.fatal) {
                load = restored
                publish(restored.config)
            }
        }
    }

    private suspend fun onRead(host: String, own: Long, read: UiConfigRead, sink: String?) {
        val next =
            withContext(Dispatchers.Main) {
                if (this@UiConfigViewModel.host != host || epoch != own) return@withContext null
                val lastGood = load?.config
                val next: UiConfigLoad? =
                    when (read) {
                        is UiConfigRead.Text -> {
                            if (read.text == lastText && load != null) return@withContext null
                            lastText = read.text
                            parseUiConfig(read.text, schema, lastGood)
                        }
                        UiConfigRead.TooLarge,
                        UiConfigRead.NotText -> {
                            lastText = null
                            val message =
                                if (read == UiConfigRead.TooLarge)
                                    UiR.string.ui_config_file_too_large
                                else UiR.string.ui_config_not_text_file
                            uiConfigBroken(UiMessage.Text(message), lastGood)
                        }
                        UiConfigRead.Absent -> {
                            lastText = null
                            null
                        }
                        UiConfigRead.Unavailable -> {
                            // Nothing to read on the phone: no file. Elsewhere keep what the host
                            // said last.
                            if (host == "local") lastText = null else return@withContext null
                            null
                        }
                    }
                load = next
                publish(next?.config)
                Result.success(next)
            } ?: return
        val parsed = next.getOrNull()
        when {
            parsed == null && read != UiConfigRead.Unavailable ->
                source.rememberUiConfig(host, null)
            parsed != null && !parsed.fatal && read is UiConfigRead.Text ->
                source.rememberUiConfig(host, read.text)
        }
        if (sink != null) writeStatus(sink, next.getOrNull())
    }

    private fun writeStatus(dir: String, load: UiConfigLoad?) {
        val strings = app.resources.uiStrings()
        val body = load?.let {
            buildJsonObject {
                put("version", schema.version)
                put("applied", it.appliedCount)
                put("fatal", it.fatal)
                put("usingPrevious", it.fromLastGood)
                putJsonArray("issues") {
                    for (issue in it.issues) addJsonObject {
                        put("pointer", issue.pointer)
                        put("kind", issue.kind.wire)
                        put("message", issue.message.render(strings))
                    }
                }
            }
        }
        source.writeUiConfigStatus(dir, body?.toUiPayload() as? ToolPayload.Obj)
    }
}
