package dev.fughetta.dshcompose.engine.runtime

// Older app versions could save the session-only Auto preset as the default for new chats
// (`permission.defaultPreset: auto`; #26 stopped offering it). DSH 0.2.1 resolves that default
// when its permission presets start, before Auto is registered, so the plugin fails on every
// launch, and neither `settings/mutate` nor the plugin-config bridge can edit an inactive plugin.
// The on-device engine's settings layer is a file the app can reach, so the app takes the value
// out of it and restarts DSH. A remote host offers no method that could, and is left alone.

import android.util.Log
import java.io.File

object AutoPresetRepair {
    /** The main profile's settings layer, where DSH's settings forms write. */
    const val MAIN_PROFILE_PATCH = "root/.dsh/profiles/compose/cordis.patch.yml"

    /**
     * Takes a stored Auto default out of [rootfs]'s main profile. True when the file changed and
     * DSH needs a restart; nothing to take out, nothing happens.
     */
    fun clear(rootfs: File): Boolean {
        val file = File(rootfs, MAIN_PROFILE_PATCH)
        return try {
            if (!file.isFile) return false
            val fixed = withoutAutoDefaultPreset(file.readText()) ?: return false
            val tmp = File(file.path + ".dsh-compose-tmp")
            tmp.writeText(fixed)
            if (!tmp.renameTo(file)) error("rename failed")
            Log.i(TAG, "cleared the stored Auto default for new chats")
            true
        } catch (error: Exception) {
            // The next connect tries again.
            Log.w(TAG, "could not clear the stored Auto default: $error")
            false
        }
    }

    /**
     * [patch] without `defaultPreset: auto` in its `permission` rows (the whole `config` when that
     * is all it holds), or null when there is none or it is not laid out one field per line in
     * block style, as DSH writes it.
     */
    fun withoutAutoDefaultPreset(patch: String): String? {
        val lines = patch.split('\n')
        fun indent(line: String) = line.length - line.trimStart(' ').length
        fun blank(line: String) = line.isBlank() || line.trimStart().startsWith('#')
        val cuts = mutableListOf<IntRange>()
        var row = 0
        while (row < lines.size) {
            if (!lines[row].startsWith("- ")) {
                row++
                continue
            }
            var end = row + 1
            while (end < lines.size && (blank(lines[end]) || indent(lines[end]) > 0)) end++
            // The row's own fields, at indent 2 ("- " opens the first one).
            val fields =
                (row until end).filter {
                    !blank(lines[it]) && (it == row || indent(lines[it]) == 2)
                }
            fun field(at: Int) = (if (at == row) lines[at].substring(2) else lines[at]).trim()
            fun key(at: Int) = field(at).substringBefore(':').trim()
            fun value(at: Int) = unquote(stripComment(field(at).substringAfter(':', "")))
            val config = fields.firstOrNull { key(it) == "config" }
            if (
                fields.any { key(it) == "id" && value(it) == "permission" } &&
                    fields.none { key(it) == "insert" } &&
                    config != null &&
                    value(config).isEmpty()
            ) {
                val next = fields.firstOrNull { it > config } ?: end
                val children = (config + 1 until next).filter { !blank(lines[it]) }
                val depth = children.firstOrNull()?.let { indent(lines[it]) }
                val preset = children.firstOrNull {
                    indent(lines[it]) == depth &&
                        lines[it].trim().substringBefore(':').trim() == "defaultPreset"
                }
                if (
                    preset != null &&
                        unquote(stripComment(lines[preset].substringAfter(':'))) == "auto"
                ) {
                    if (children.size == 1) cuts += config..preset else cuts += preset..preset
                }
            }
            row = end
        }
        if (cuts.isEmpty()) return null
        val dropped = cuts.flatMap { it.toList() }.toSet()
        return lines.filterIndexed { i, _ -> i !in dropped }.joinToString("\n")
    }

    private fun stripComment(value: String): String {
        val hash = value.indexOf(" #")
        return (if (hash < 0) value else value.substring(0, hash)).trim()
    }

    private fun unquote(value: String) =
        if (value.length >= 2 && value.first() == value.last() && value.first() in "'\"")
            value.substring(1, value.length - 1)
        else value

    private const val TAG = "Permissions"
}
