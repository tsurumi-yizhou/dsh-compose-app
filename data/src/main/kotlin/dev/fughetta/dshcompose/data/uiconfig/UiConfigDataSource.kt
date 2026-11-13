package dev.fughetta.dshcompose.data.uiconfig

import android.content.Context
import android.os.FileObserver
import dev.fughetta.dshcompose.data.database.AppDatabase
import dev.fughetta.dshcompose.data.database.CachedDocumentRow
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.uiconfig.UiConfigPorts
import dev.fughetta.dshcompose.domain.uiconfig.UiConfigRead
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFileChange
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/**
 * DSH home from the skill's SKILL.md path: `<home>/skills/dsh-compose-ui/SKILL.md` (or the folder).
 */
fun dshHomeFromSkillPath(path: String): String? {
    val parts = path.split('/').toMutableList()
    while (parts.isNotEmpty() && parts.last().isEmpty()) parts.removeAt(parts.lastIndex)
    if (parts.lastOrNull() == "SKILL.md") parts.removeAt(parts.lastIndex)
    if (parts.size < 3 || parts.last() != "dsh-compose-ui" || parts[parts.size - 2] != "skills")
        return null
    return parts.subList(0, parts.size - 2).joinToString("/").ifEmpty { null }
}

/** Local rootfs/inotify and remote workspace streams, with a last-good document cache. */
class UiConfigDataSource(
    context: Context,
    private val rootfs: () -> File?,
    private val mayWrite: () -> Boolean,
    private val foreground: () -> Boolean,
    private val maxBytes: () -> Int,
) {
    private val documents = AppDatabase.get(context).documents()
    private var lastStatus: String? = null
    @Volatile
    var unresolved = false
        private set

    fun resetStatus() {
        lastStatus = null
    }

    suspend fun restore(host: String): String? =
        try {
            documents.read("ui_config", host)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            android.util.Log.e("UiConfig", "ui-config/cache-read-failed")
            null
        }

    suspend fun remember(host: String, text: String?) {
        try {
            if (text == null) documents.remove("ui_config", host)
            else documents.put(CachedDocumentRow("ui_config", host, text))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            android.util.Log.e("UiConfig", "ui-config/cache-write-failed")
        }
    }

    suspend fun followLocal(kicks: Channel<Unit>, onRead: suspend (UiConfigRead, File?) -> Unit) {
        var observer: FileObserver? = null
        var watched: File? = null
        try {
            while (currentCoroutineContext().isActive) {
                val root = rootfs()
                val home = root?.let { File(it, "root/.dsh") }
                val dir = home?.let { File(it, "dsh-compose") }
                if (dir == null) {
                    observer?.stopWatching()
                    observer = null
                    watched = null
                    onRead(UiConfigRead.Unavailable, null)
                } else {
                    if (!dir.isDirectory && home.isDirectory && mayWrite()) dir.mkdirs()
                    if (dir.isDirectory && watched != dir) {
                        observer?.stopWatching()
                        observer =
                            configObserver(dir) { kicks.trySend(Unit) }.also { it.startWatching() }
                        watched = dir
                    }
                    onRead(readLocal(dir), dir)
                }
                kicks.receive()
                delay(300)
                kicks.tryReceive()
            }
        } finally {
            observer?.stopWatching()
        }
    }

    @Suppress("DEPRECATION") // FileObserver(File) needs API 29.
    private fun configObserver(dir: File, changed: () -> Unit) =
        object :
            FileObserver(
                dir.path,
                CLOSE_WRITE or
                    MOVED_TO or
                    MOVED_FROM or
                    DELETE or
                    CREATE or
                    DELETE_SELF or
                    MOVE_SELF,
            ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "ui.json" || event and (DELETE_SELF or MOVE_SELF) != 0) changed()
            }
        }

    private fun readLocal(dir: File): UiConfigRead {
        val file = File(dir, "ui.json")
        return try {
            if (!file.isFile) return UiConfigRead.Absent
            if (file.length() > maxBytes()) return tooLarge()
            val bytes = file.readBytes()
            if (bytes.size > maxBytes()) tooLarge() else UiConfigRead.Text(bytes.decodeToString())
        } catch (_: java.io.IOException) {
            UiConfigRead.Absent
        }
    }

    private fun tooLarge() = UiConfigRead.TooLarge

    /** `dsh-compose/ui.status.json`: written only when it changes, removed without a ui.json. */
    fun writeStatus(dir: File, body: JsonObject?) {
        if (!dir.isDirectory || !mayWrite()) return
        val file = File(dir, "ui.status.json")
        try {
            if (body == null) {
                lastStatus = null
                file.delete()
                return
            }
            val key = body.toString()
            if (key == lastStatus && file.exists()) return
            val text =
                prettyJson.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(mapOf("readAt" to JsonPrimitive(Instant.now().toString())) + body),
                )
            val tmp = File(dir, "ui.status.json.tmp")
            tmp.writeText("$text\n")
            if (!tmp.renameTo(file)) tmp.delete() else lastStatus = key
        } catch (_: Exception) {
            // Best effort: the app works without it.
        }
    }

    suspend fun followRemote(
        client: UiConfigPorts,
        kicks: Channel<Unit>,
        onRead: suspend (UiConfigRead, File?) -> Unit,
    ) {
        while (currentCoroutineContext().isActive) {
            val target =
                try {
                    locate(client)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            currentCoroutineContext().ensureActive()
            if (target == null) {
                unresolved = true
                onRead(UiConfigRead.Unavailable, null)
                kicks.receive()
                continue
            }
            unresolved = false
            val (session, path) = target
            // Until the conversation goes away; then look for another.
            coroutineScope {
                val follower = launch { followChanges(client, session, path, kicks) }
                while (true) {
                    val read = readRemote(client, session, path) ?: break
                    onRead(read, null)
                    kicks.receive()
                    delay(300)
                    kicks.tryReceive()
                }
                follower.cancel()
            }
        }
    }

    /** The most recent top-level conversation and the file's path (null: not locatable now). */
    private suspend fun locate(client: UiConfigPorts): Pair<String, String>? {
        val session =
            client.sessions
                .list()
                .sortedWith(
                    compareBy<dev.fughetta.dshcompose.domain.sessions.SessionSummary> {
                            (if (it.parentSessionId != null) 2 else 0) + (if (it.blank) 1 else 0)
                        }
                        .thenByDescending { it.updatedAt },
                )
                .firstOrNull()
                ?.sessionId
                ?.ifEmpty { null } ?: return null
        val skill =
            try {
                client.skills.list(session).firstOrNull { it.name == "dsh-compose-ui" }?.path
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        val home =
            skill?.let(::dshHomeFromSkillPath)
                ?: client.sessions.hostHome?.trimEnd('/')?.ifEmpty { null }?.let { "$it/.dsh" }
                ?: return null
        return session to "$home/dsh-compose/ui.json"
    }

    /** One full read; null when the conversation went away (look for another). */
    private suspend fun readRemote(
        client: UiConfigPorts,
        session: String,
        path: String,
    ): UiConfigRead? =
        try {
            var page = client.files.readText(session, path, 1)
            val text = StringBuilder(page.lines.joinToString("\n"))
            var pages = 1
            var tooLarge = false
            while (!page.eof && page.count > 0 && pages < 16) {
                page = client.files.readText(session, path, page.offset + page.count)
                text.append('\n').append(page.lines.joinToString("\n"))
                pages++
                if (text.length > maxBytes()) {
                    tooLarge = true
                    break
                }
            }
            val body = text.toString()
            if (tooLarge || body.toByteArray().size > maxBytes()) tooLarge()
            else UiConfigRead.Text(body)
        } catch (e: OperationFailure) {
            when (e.code) {
                "workspace-file/not-found",
                "workspace-file/not-regular-file" -> UiConfigRead.Absent
                "workspace-file/too-large" -> tooLarge()
                "workspace-file/not-text" -> UiConfigRead.NotText
                "workspace-file/unknown-workspace",
                "session/not-found" -> null
                else -> UiConfigRead.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Lost link or timeout: the next kick or reconnect reads again.
            UiConfigRead.Unavailable
        }

    /**
     * `workspaceFiles/changes` when the host has it (it also reports a missing file appearing),
     * else a look every minute while the app is in front.
     */
    private suspend fun followChanges(
        client: UiConfigPorts,
        session: String,
        path: String,
        kicks: Channel<Unit>,
    ) {
        try {
            client.files.changes(session, path).collect {
                if (it is WorkspaceFileChange.Changed) kicks.trySend(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        while (true) {
            delay(60_000)
            if (foreground()) kicks.trySend(Unit)
        }
    }

    private companion object {
        val prettyJson = Json { prettyPrint = true }
    }
}
