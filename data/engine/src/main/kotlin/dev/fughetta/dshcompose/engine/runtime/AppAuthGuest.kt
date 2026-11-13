package dev.fughetta.dshcompose.engine.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Subscription sign-in (ChatGPT, Claude) for the local engine. DSH 0.2.1 carries both flows in its
 * built-in `llm-pi-ai` adapter but gives clients no way to start one; the app's auth bridge plugin
 * (`app/src/main/assets/engine/auth/`, Remote service `appAuth`) does, and declares the provider's
 * route once a sign-in succeeds.
 *
 * The Android assets supply the bridge modules in [GUEST_DIR]. The Compose auth bundle inserts the
 * plugin; the profile controls whether that bundle is enabled.
 */
object AppAuthGuest {
    const val GUEST_DIR = "/opt/dsh-app/auth"
    const val ENTRY = "$GUEST_DIR/index.mjs"

    /** Subscription bridge modules bundled as Android assets. */
    private const val ASSET_DIR = "engine/auth"
    private val MODULES = listOf("index.mjs", "bridge.mjs")

    val PATCH_YAML =
        """# Compose subscription sign-in bundle (ChatGPT, Claude).
- insert:
    - id: dsh-app-auth
      name: $ENTRY
"""

    /** Copies the bundle's bridge modules; false stops startup if installation failed. */
    fun writeFiles(context: Context, rootfs: File): Boolean {
        return try {
            val dir = File(rootfs, GUEST_DIR.removePrefix("/"))
            if (Files.isSymbolicLink(dir.toPath()) || (dir.exists() && !dir.isDirectory))
                dir.delete()
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
            for (name in MODULES) {
                val bytes = context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                writeIfChanged(File(dir, name), bytes)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sibling temp file renamed over [target]; only a regular file is compared (never a link or
     * FIFO).
     */
    private fun writeIfChanged(target: File, bytes: ByteArray) {
        if (
            Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                target.readBytes().contentEquals(bytes)
        )
            return
        val tmp = File(target.parentFile, ".${target.name}.dsh-app-${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) throw IOException("could not replace ${target.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
