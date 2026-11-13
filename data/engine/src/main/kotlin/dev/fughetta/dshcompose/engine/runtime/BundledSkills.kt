package dev.fughetta.dshcompose.engine.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.*

/** Deploy APK skills before the engine discovers its skill catalog. */
internal object BundledSkills {
    fun install(context: Context, root: File) =
        install(root) { path -> context.assets.open(path).bufferedReader().use { it.readText() } }

    internal fun install(root: File, readAsset: (String) -> String) {
        File(root, "root/.dsh").mkdirs()
        for (name in
            listOf(
                "dsh-compose-ui",
                "dsh-compose-plugin",
                "dsh-compose-guide",
                "dsh-compose-support",
            )) {
            val base = "skills/$name"
            val manifest = Json.parseToJsonElement(readAsset("$base/manifest.json")).jsonObject
            val files =
                manifest.getValue("files").jsonObject.entries.associate { (asset, destination) ->
                    destination.jsonPrimitive.content to readAsset("$base/$asset")
                }
            val request =
                requireNotNull(
                    ManagedSkillRequest.parse(
                        mapOf(
                            "requestId" to "bundled-$name",
                            "expectedRoot" to root.canonicalPath,
                            "expectedToken" to "setup",
                            "expectedGeneration" to 1L,
                            "name" to name,
                            "files" to files,
                        ),
                    ),
                )
            val outcome = ManagedSkillInstaller.install(root, request) { true }
            if (outcome.reason != null || outcome.result == "noHome")
                throw IOException("BUNDLED_SKILL_INSTALL_FAILED: $name")
        }
    }
}
