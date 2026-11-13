package dev.fughetta.dshcompose.engine.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal val profileManifestJson = runtimeJson

/** The application's sole main profile, including its user-installed bundles and settings. */
object RuntimeProfile {
    const val NAME = "compose"
    const val ARGS = "--profile compose"
    const val DIRECTORY = "root/.dsh/profiles/compose"

    fun profileArg(arg: String): String? =
        when {
            arg.contains("{DSH_PROFILE_ARGS}") -> arg.replace("{DSH_PROFILE_ARGS}", ARGS)
            arg.indexOf("/usr/local/bin/dsh compose ").let { first ->
                first >= 0 && arg.indexOf("/usr/local/bin/dsh compose ", first + 1) < 0
            } -> arg.replace("/usr/local/bin/dsh compose ", "/usr/local/bin/dsh $ARGS ")
            else -> null
        }

    internal fun migrateBundles(bundles: List<String>): List<String> =
        bundles
            .filterNot { it == "dsh-compose-magpie" }
            .map { if (it == "@deepseek-ai/dsh-web-app") "dsh-compose-app" else it }
            .distinct()

    fun prepare(rootfs: File) {
        val previous = File(rootfs, "root/.dsh/profiles/web")
        val current = File(rootfs, DIRECTORY)
        if (previous.exists()) {
            if (current.exists()) throw IOException("COMPOSE_PROFILE_CONFLICT")
            Files.move(previous.toPath(), current.toPath())
        }
        val manifest = File(current, "package.json")
        if (!manifest.exists()) {
            if (!current.isDirectory && !current.mkdirs())
                throw IOException("COMPOSE_PROFILE_CREATE_FAILED")
            val pending = File(current, "package.json.pending")
            pending.writeText(
                """{"name":"dsh-profile-compose","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","dsh-compose-app"]}}}
""",
            )
            Files.move(pending.toPath(), manifest.toPath())
        } else {
            migrateManifest(manifest)
        }
    }

    private fun migrateManifest(manifest: File) {
        try {
            val root =
                try {
                    Json.parseToJsonElement(manifest.readText()) as? JsonObject
                } catch (_: SerializationException) {
                    return
                } ?: return
            val dsh = root.obj("dsh") ?: return
            val profile = dsh.obj("profile") ?: return
            val names = profile.strings("bundles") ?: return
            if ("@deepseek-ai/dsh-web-app" !in names && "dsh-compose-magpie" !in names) return
            val migrated =
                profile.with(
                    "bundles",
                    JsonArray(migrateBundles(names).map { JsonPrimitive(it) }),
                )
            val updated = root.with("dsh", dsh.with("profile", migrated))
            RuntimeFiles.writeAtomic(
                manifest,
                profileManifestJson.encodeToString(JsonElement.serializer(), updated) + "\n",
            )
        } catch (e: IOException) {
            throw IOException("COMPOSE_PROFILE_MIGRATE_FAILED", e)
        }
    }
}
