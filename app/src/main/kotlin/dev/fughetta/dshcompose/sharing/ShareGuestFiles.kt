package dev.fughetta.dshcompose.sharing

import android.content.Context
import dev.fughetta.dshcompose.domain.sharing.ShareFormatException
import dev.fughetta.dshcompose.domain.sharing.SharedSkill
import dev.fughetta.dshcompose.engine.runtime.LastKnownGood
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Local engine-owned skill/agent files, outside the app's preferences. */
class ShareGuestFiles(context: Context) {
    private val runtime = File(context.filesDir, "runtime")
    private val root = File(runtime, "rootfs").toPath()
    val available
        get() = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)

    private fun path(relative: String, createParent: Boolean = false): Path {
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) || target == root)
            throw ShareFormatException("share/unsafe-local-path")
        var current = root
        val pieces = root.relativize(target).toList()
        for ((index, part) in pieces.withIndex()) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw ShareFormatException("share/unsafe-local-path")
            if (
                index < pieces.lastIndex &&
                    createParent &&
                    !Files.exists(current, LinkOption.NOFOLLOW_LINKS)
            )
                Files.createDirectory(current)
            if (
                index < pieces.lastIndex &&
                    Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            )
                throw ShareFormatException("share/unsafe-local-path")
        }
        return target
    }

    private fun text(path: Path, limit: Int): String? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit * 4L)
            return null
        return Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            .use { channel ->
                java.nio.channels.Channels.newInputStream(channel).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > limit * 4) return@use null
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray().toString(Charsets.UTF_8).takeIf { it.length <= limit }
                }
            }
    }

    fun userAgents(): String? =
        listOf("root/.dsh/AGENTS.md", "root/.agents/AGENTS.md").firstNotNullOfOrNull { relative ->
            text(path(relative), 64 * 1024)?.takeIf {
                it.isNotBlank() && !it.trimStart().startsWith("<!-- dsh-app:managed")
            }
        }

    fun importedAgents(): String? = text(path("root/.agents/AGENTS.md"), 64 * 1024)

    fun skills(): List<SharedSkill> {
        val found = linkedMapOf<String, SharedSkill>()
        for (relative in listOf("root/.dsh/skills", "root/.agents/skills")) {
            val dir = path(relative)
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
            Files.newDirectoryStream(dir).use { entries ->
                entries
                    .toList()
                    .sortedBy { it.fileName.toString() }
                    .forEach { entry ->
                        val base = entry.fileName.toString()
                        if (base.startsWith('.')) return@forEach
                        try {
                            val name =
                                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) base
                                else base.removeSuffix(".md")
                            if (
                                name in found ||
                                    !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                            )
                                return@forEach
                            val files =
                                when {
                                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ->
                                        readSkill(entry)
                                    base.endsWith(".md") ->
                                        text(entry, 64 * 1024)?.let { mapOf("SKILL.md" to it) }
                                    else -> null
                                } ?: return@forEach
                            found[name] = SharedSkill.checked(name, files)
                        } catch (_: Exception) {}
                    }
            }
        }
        return found.values.take(32)
    }

    private fun readSkill(dir: Path): Map<String, String>? {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null
        val files = linkedMapOf<String, String>()
        Files.walkFileTree(
            dir,
            emptySet(),
            5,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    path: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (path != dir && !SharedSkill.safePath(dir.relativize(path).toString()))
                        throw ShareFormatException("share/unsafe-skill")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = dir.relativize(file).toString()
                    if (!attrs.isRegularFile || !SharedSkill.safePath(relative) || files.size >= 12)
                        throw ShareFormatException("share/unsafe-skill")
                    val value =
                        text(file, 64 * 1024) ?: throw ShareFormatException("share/unsafe-skill")
                    files[relative] = value
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files.takeIf { "SKILL.md" in it }
    }

    fun skill(name: String): SharedSkill? {
        if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
            throw ShareFormatException("share/unsafe-skill")
        return readSkill(path("root/.dsh/skills/$name"))?.let { SharedSkill(name, it) }
    }

    fun pinProfile() {
        val snapshot =
            LastKnownGood.read(root.toFile())
                ?: throw ShareFormatException("share/restore-point-failed")
        LastKnownGood.save(File(runtime, "lkg/lkg.json"), snapshot, System.currentTimeMillis())
    }

    fun replaceSkill(skill: SharedSkill) {
        SharedSkill.checked(skill.name, skill.files)
        val target = path("root/.dsh/skills/${skill.name}", true)
        val existing =
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                readSkill(target) ?: throw ShareFormatException("share/unsafe-skill")
            else null
        val parent = target.parent
        val staged = Files.createTempDirectory(parent, ".share-${skill.name}-")
        try {
            skill.files.forEach { (relative, value) ->
                val file = staged.resolve(relative)
                Files.createDirectories(file.parent)
                Files.write(
                    file,
                    value.toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,
                )
            }
            if (existing != null) {
                val backup =
                    path(
                        "root/.dsh/backups/share-import-${UUID.randomUUID()}/skills/${skill.name}",
                        true,
                    )
                Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE)
                try {
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (error: Throwable) {
                    Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE)
                    throw error
                }
            } else Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) staged.toFile().deleteRecursively()
        }
    }

    /** Removes the user's AGENTS.md, keeping a backup like [replaceAgents]. */
    fun removeAgents() {
        val target = path("root/.agents/AGENTS.md")
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw ShareFormatException("share/unsafe-local-path")
        val backup = path("root/.dsh/backups/share-import-${UUID.randomUUID()}/AGENTS.md", true)
        Files.move(target, backup)
    }

    fun replaceAgents(text: String) {
        if (text.length > 64 * 1024) throw ShareFormatException("share/too-large")
        val target = path("root/.agents/AGENTS.md", true)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw ShareFormatException("share/unsafe-local-path")
            val backup = path("root/.dsh/backups/share-import-${UUID.randomUUID()}/AGENTS.md", true)
            Files.copy(target, backup)
        }
        val staged = Files.createTempFile(target.parent, ".share-agents-", ".md")
        try {
            Files.write(staged, text.toByteArray(Charsets.UTF_8))
            Files.move(
                staged,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}
