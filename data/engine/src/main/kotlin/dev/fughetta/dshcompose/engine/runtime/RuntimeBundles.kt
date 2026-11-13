package dev.fughetta.dshcompose.engine.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.*

/** Application packages resolved by DSH's installation anchor, before the user profile layer. */
object RuntimeBundles {
    private val patches =
        linkedMapOf(
            "dsh-compose-search" to SessionSearchGuest.PATCH_YAML,
            "dsh-compose-auth" to AppAuthGuest.PATCH_YAML,
        )

    fun install(context: Context, rootfs: File) {
        if (!AppAuthGuest.writeFiles(context, rootfs))
            throw IOException("COMPOSE_AUTH_INSTALL_FAILED")
        install(rootfs)
    }

    internal fun install(rootfs: File) {
        for ((name, patch) in patches) {
            val directory = File(rootfs, "opt/dsh/node_modules/$name")
            if (!directory.isDirectory && !directory.mkdirs())
                throw IOException("COMPOSE_BUNDLE_INSTALL_FAILED")
            RuntimeFiles.writeAtomic(File(directory, "cordis.patch.yml"), patch)
            val packageJson = buildJsonObject {
                put("name", name)
                put("private", true)
                put("exports", buildJsonObject { put("./package.json", "./package.json") })
                put(
                    "dsh",
                    buildJsonObject {
                        put(
                            "bundle",
                            buildJsonObject { put("patch", "./cordis.patch.yml") },
                        )
                    },
                )
            }
            RuntimeFiles.writeAtomic(
                File(directory, "package.json"),
                profileManifestJson.encodeToString(JsonElement.serializer(), packageJson) + "\n",
            )
        }
        val manifest = File(rootfs, "${RuntimeProfile.DIRECTORY}/package.json")
        val root = Json.parseToJsonElement(manifest.readText()) as? JsonObject
        val dsh = root?.obj("dsh") ?: return
        val profile = dsh.obj("profile") ?: return
        val original = profile.strings("bundles").orEmpty()
        val bundles = original.filterNot { it == "dsh-compose-speech" }.toMutableList()
        // Initialize once: a user may disable these bundles through the existing plugin manager.
        val initialized = dsh.boolean("runtimeBundlesInitialized") == true
        if (!initialized) {
            val existing = bundles.toSet()
            patches.keys.filterNot { it in existing }.forEach { bundles += it }
        }
        if (!initialized || bundles != original) {
            val updatedProfile =
                profile.with("bundles", JsonArray(bundles.map { JsonPrimitive(it) }))
            val updatedDsh =
                dsh.with("profile", updatedProfile)
                    .with("runtimeBundlesInitialized", JsonPrimitive(true))
            val updated = root.with("dsh", updatedDsh)
            RuntimeFiles.writeAtomic(
                manifest,
                profileManifestJson.encodeToString(JsonElement.serializer(), updated) + "\n",
            )
        }
        val retired = File(rootfs, "opt/dsh/node_modules/dsh-compose-speech")
        if (retired.exists() && !retired.deleteRecursively())
            throw IOException("COMPOSE_SPEECH_REMOVE_FAILED")
    }
}
