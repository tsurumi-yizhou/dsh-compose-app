package dev.fughetta.dshcompose.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.i18n.AppStrings
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Copies external content URIs off the UI thread, then delivers a share to native state. */
class Shares(private val context: Context, private val onShare: (Map<String, Any?>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var slotSeq = 0
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-share").apply { isDaemon = true }
    }

    fun handleIntent(intent: Intent): Boolean {
        if (
            intent.getBooleanExtra(EXTRA_TAKEN, false) ||
                intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        )
            return false
        return take(intent).also { if (it) intent.putExtra(EXTRA_TAKEN, true) }
    }

    fun close() {
        worker.shutdown()
    }

    /** Starts reading what [intent] shares; false when it isn't a share. */
    private fun take(intent: Intent): Boolean {
        if (intent.action != ACTION_SHARE) return false
        val share =
            Received(
                text = textOf(intent),
                subject =
                    intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() },
                streams = streamsOf(intent),
                type = intent.type,
            )
        if (share.text == null && share.streams.isEmpty()) return true
        worker.execute {
            val event =
                try {
                    read(share)
                } catch (_: Throwable) {
                    mapOf(
                        "text" to share.text,
                        "subject" to share.subject,
                        "files" to emptyList<Any>(),
                        "skipped" to share.streams.map { skip(null, "unreadable") },
                    )
                }
            main.post { deliver(event) }
        }
        return true
    }

    private class Received(
        val text: String?,
        val subject: String?,
        val streams: List<Uri>,
        val type: String?,
    )

    // Reading

    private fun read(share: Received): Map<String, Any?> {
        val files = ArrayList<Map<String, Any?>>()
        val skipped = ArrayList<Map<String, Any?>>()
        if (share.streams.isNotEmpty()) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            prune(dir)
            val slot =
                File(dir, "${System.currentTimeMillis().toString(36)}-${slotSeq++}").apply {
                    mkdirs()
                }
            var total = 0L
            for ((index, uri) in share.streams.withIndex()) {
                if (index >= MAX_ITEMS) {
                    skipped += skip(null, "too_many")
                    continue
                }
                val item = readOne(uri, slot, if (share.streams.size == 1) share.type else null)
                if (item.file == null) {
                    skipped += skip(item.name, item.problem ?: "unreadable")
                    continue
                }
                val nextTotal = total + item.file.length()
                if (nextTotal > MAX_TOTAL_BYTES) {
                    item.file.delete()
                    skipped += skip(item.name, "too_large")
                    continue
                }
                total = nextTotal
                files +=
                    mapOf(
                        "path" to item.file.absolutePath,
                        "name" to item.file.name,
                        "mime" to item.mime,
                        "size" to item.file.length(),
                    )
            }
        }
        return mapOf(
            "text" to share.text,
            "subject" to share.subject,
            "files" to files,
            "skipped" to skipped,
        )
    }

    private class Item(
        val file: File?,
        val name: String?,
        val mime: String?,
        val problem: String? = null,
    )

    private fun readOne(uri: Uri, slot: File, fallbackType: String?): Item {
        if (!readable(uri)) return Item(null, null, null, "unreadable")
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        try {
            resolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
                ?.use {
                    if (it.moveToFirst()) {
                        val n = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = it.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !it.isNull(n)) name = it.getString(n)
                        if (s >= 0 && !it.isNull(s)) size = it.getLong(s)
                    }
                }
        } catch (_: Exception) {
            // Some providers answer only openInputStream.
        }
        val mime =
            (try {
                resolver.getType(uri)
            } catch (_: Exception) {
                null
            }) ?: fallbackType?.takeUnless { it.contains('*') }
        val shown =
            ShareNames.withExtension(
                ShareNames.safeName(
                    name
                        ?: uri.lastPathSegment
                        ?: ShareNames.fallbackName(
                            mime,
                            AppStrings.get(context, R.string.share_file),
                            AppStrings.get(context, R.string.share_image),
                            AppStrings.get(context, R.string.share_video),
                            AppStrings.get(context, R.string.share_audio),
                            AppStrings.get(context, R.string.share_text),
                        ),
                ),
                extensionOf(mime),
            )
        val target = ShareNames.unique(slot, shown)

        if (ShareImages.scalable(mime)) {
            try {
                ShareImages.prepare(resolver, uri, target)?.let {
                    return Item(it, shown, ShareImages.mimeOf(it), null)
                }
            } catch (_: Throwable) {
                // Undecodable here: sent as it is.
            }
        }
        if (size > MAX_FILE_BYTES) return Item(null, shown, mime, "too_large")
        val input =
            try {
                resolver.openInputStream(uri)
            } catch (_: Exception) {
                null
            } ?: return Item(null, shown, mime, "unreadable")
        try {
            input.use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_FILE_BYTES) {
                            out.close()
                            target.delete()
                            return Item(null, shown, mime, "too_large")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (_: IOException) {
            target.delete()
            return Item(null, shown, mime, "unreadable")
        }
        return Item(target, shown, mime)
    }

    private fun extensionOf(mime: String?): String? =
        when (mime) {
            null -> null
            ShareNames.SHARE_CODE_MIME -> ShareNames.SHARE_CODE_EXTENSION
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        }

    /** Another app's `content:` URI. */
    private fun readable(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() != "content") return false
        val authority = uri.authority ?: return false
        val own = context.packageName
        return authority.split(';').none { it == own || it.startsWith("$own.") }
    }

    private fun skip(name: String?, reason: String): Map<String, Any?> =
        mapOf("name" to name, "reason" to reason)

    /** Keeps the last few shares (the app reads them right away; a share code stays a while). */
    private fun prune(dir: File) {
        val slots = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        for (old in slots.drop(KEEP_SLOTS - 1)) old.deleteRecursively()
    }

    // Delivery

    private fun deliver(event: Map<String, Any?>) {
        onShare(event)
    }

    companion object {
        const val ACTION_SHARE = "dev.fughetta.dshcompose.action.SHARE"

        /** The action the sender used (ACTION_SEND / ACTION_SEND_MULTIPLE). */
        const val EXTRA_SENT_AS = "dev.fughetta.dshcompose.share.SENT_AS"
        private const val EXTRA_TAKEN = "dev.fughetta.dshcompose.share.TAKEN"

        /** The composer's limit. */
        const val MAX_ITEMS = 9

        /** `maxFileBytes` there. */
        const val MAX_FILE_BYTES = 200L * 1024 * 1024

        /** All files of one share: the memory budget in the app until sent. */
        const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
        const val MAX_TEXT_CHARS = 200_000
        private const val KEEP_SLOTS = 4

        /** The shared streams: EXTRA_STREAM (one or a list), else the ClipData URIs. */
        @Suppress("DEPRECATION")
        fun streamsOf(intent: Intent): List<Uri> {
            val found = LinkedHashSet<Uri>()
            val extra: Any? =
                try {
                    intent.extras?.get(Intent.EXTRA_STREAM)
                } catch (_: Exception) {
                    null
                }
            when (extra) {
                is Uri -> found += extra
                is ArrayList<*> -> for (item in extra) if (item is Uri) found += item
            }
            if (found.isEmpty()) {
                val clip = intent.clipData
                if (clip != null)
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { found += it }
            }
            return found.toList()
        }

        /** EXTRA_TEXT (one, or a list for SEND_MULTIPLE), else ClipData text; capped. */
        fun textOf(intent: Intent): String? {
            val parts = ArrayList<String>()
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { parts += it.toString() }
            if (parts.isEmpty()) {
                intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.forEach {
                    parts += it.toString()
                }
            }
            if (parts.isEmpty()) {
                val clip = intent.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val item = clip.getItemAt(i)
                        if (item.uri == null) item.text?.let { parts += it.toString() }
                    }
                }
            }
            val text = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            return text.ifEmpty { null }?.take(MAX_TEXT_CHARS)
        }
    }
}
