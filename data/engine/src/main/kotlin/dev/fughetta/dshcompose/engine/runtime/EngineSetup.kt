package dev.fughetta.dshcompose.engine.runtime

import android.content.Context
import java.io.File
import java.io.IOException

/** Installs the engine into an extracted guest before publishing it as ready. */
internal class EngineSetup(private val context: Context, private val manifest: RuntimeManifest) {
    private fun version() =
        context.assets.open("runtime/engine-version.txt").bufferedReader().use {
            it.readText().trim()
        }

    fun isInstalled(root: File): Boolean =
        File(root, "opt/dsh/engine.version").let { it.isFile && it.readText().trim() == version() }

    fun install(root: File, onStage: (String) -> Unit) {
        val packageDir = File(root, "tmp/dsh-engine-setup")
        packageDir.deleteRecursively()
        packageDir.mkdirs()
        try {
            context.assets.open("runtime/engine.tgz").use {
                TarExtractor(packageDir) {}.extract(it, "engine.tgz")
            }
            GuestSetup.writeResolvConf(context, root)
            val tmp = File(context.cacheDir, "engine-setup-tmp").apply { mkdirs() }
            val variables =
                mapOf(
                    "{NATIVE_LIB_DIR}" to context.applicationInfo.nativeLibraryDir,
                    "{ROOTFS}" to root.canonicalPath,
                    "{TMP}" to tmp.absolutePath,
                    "{FILES_DIR}" to context.filesDir.absolutePath,
                )
            fun expand(value: String) =
                variables.entries.fold(value) { result, (key, replacement) ->
                    result.replace(key, replacement)
                }
            val shell = manifest.launchArgv.indexOf("/bin/sh")
            check(shell >= 0) { "runtime has no setup shell" }
            val argv =
                manifest.launchArgv
                    .take(shell)
                    .filter { it != "{GUEST_ENV}" }
                    .map { arg ->
                        if (arg.startsWith("NODE_OPTIONS="))
                            "NODE_OPTIONS=" + DirectLaunch.prootNodeOptions(arg.substringAfter('='))
                        else expand(arg)
                    } +
                    listOf(
                        "DSH_SETUP_NODE_VERSION=${manifest.nodeVersion}",
                        "DSH_SETUP_ABI=${manifest.abi}",
                        "/bin/sh",
                        "/tmp/dsh-engine-setup/install.sh",
                    )
            val log = File(context.filesDir, "runtime/logs/engine-install.log")
            log.parentFile!!.mkdirs()
            val builder = ProcessBuilder(argv).directory(context.filesDir).redirectErrorStream(true)

            builder.environment().apply {
                clear()
                put("PATH", "/system/bin:/system/xbin")
                put("HOME", context.filesDir.absolutePath)
                put("TMPDIR", tmp.absolutePath)
                put("ANDROID_ROOT", System.getenv("ANDROID_ROOT") ?: "/system")
                put("ANDROID_DATA", System.getenv("ANDROID_DATA") ?: "/data")
                manifest.launchEnv.forEach { (key, value) -> put(key, expand(value)) }
            }
            val process = builder.start()
            try {
                log.bufferedWriter().use { output ->
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val step = line.removePrefix("DSH_SETUP ")
                            if (
                                line.startsWith("DSH_SETUP ") &&
                                    step in setOf("tools", "node", "pnpm", "engine")
                            )
                                onStage(step)
                            else {
                                output.appendLine(line)
                                output.flush()
                            }
                        }
                    }
                }
                if (process.waitFor() != 0) throw IOException("ENGINE_DEPENDENCY_INSTALL_FAILED")
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            // dpkg needs PRoot's link emulation on Android. Materialize its links before
            // staging moves or activation restores the user's previous /root.
            when (
                L2sMigration(
                        root,
                        flush = ::syncFilesystems,
                        guestBinds = L2sMigration.bindTargets(argv, emptyList()),
                    )
                    .run()
            ) {
                is L2sMigration.Result.Done -> Unit
                is L2sMigration.Result.NotEnoughSpace ->
                    throw IOException("ENGINE_INSTALL_STORAGE_INSUFFICIENT")
            }
            File(root, "opt/dsh/engine.version").writeText(version())
        } finally {
            packageDir.deleteRecursively()
        }
    }
}
