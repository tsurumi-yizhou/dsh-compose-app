package dev.fughetta.dshcompose.engine.runtime

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Describes the Linux runtime bundled in this APK flavor (assets/runtime/manifest.json, written by
 * data/engine/runtime/build-runtime.sh). Parsed with kotlinx.serialization so the runtime keeps a
 * single typed description instead of poking a JSON object.
 */
data class RuntimeManifest(
    val runtimeVersion: String,
    val abi: String,
    val rootfsAsset: String,
    val rootfsBytes: Long,
    val rootfsSha256: String?,
    val uncompressedBytes: Long,
    val dshVersion: String,
    val nodeVersion: String,
    /** proot argv template; placeholders are expanded by [LaunchSpec]. */
    val launchArgv: List<String>,
    val launchEnv: Map<String, String>,
    /** `entries.file` in the manifest; null when the manifest does not record file totals. */
    val files: Long? = null,
    /** `directEngine.schema`; 2 means the direct-engine launcher is available. */
    val directEngineSchema: Int? = null,
) {
    /** Base extraction plus working space for npm downloads and the installed engine. */
    val installationBytes: Long
        get() =
            (uncompressedBytes.takeIf { it > 0 } ?: 600L * 1024 * 1024) * 115 / 100 +
                1536L * 1024 * 1024

    companion object {
        const val ASSET_DIR = "runtime"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): RuntimeManifest =
            parse(
                context.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use {
                    it.readText()
                },
            )

        fun parse(text: String): RuntimeManifest {
            val file = json.decodeFromString(ManifestFile.serializer(), text)
            return RuntimeManifest(
                runtimeVersion = file.runtimeVersion,
                abi = file.abi,
                rootfsAsset = file.rootfs,
                rootfsBytes = file.rootfsBytes,
                rootfsSha256 = file.rootfsSha256?.ifEmpty { null },
                uncompressedBytes = file.uncompressedBytes,
                dshVersion = file.dshVersion,
                nodeVersion = file.nodeVersion,
                launchArgv = file.launch.argv,
                launchEnv = file.launch.env,
                files = file.entries?.file,
                directEngineSchema = file.directEngine?.schema,
            )
        }
    }
}

@Serializable
internal data class ManifestFile(
    val runtimeVersion: String = "",
    val abi: String = "",
    @SerialName("rootfs") val rootfs: String = "",
    val rootfsBytes: Long = -1,
    val rootfsSha256: String? = null,
    val uncompressedBytes: Long = -1,
    val dshVersion: String = "",
    val nodeVersion: String = "",
    val launch: ManifestLaunch = ManifestLaunch(),
    val entries: ManifestEntries? = null,
    val directEngine: ManifestDirectEngine? = null,
)

@Serializable
internal data class ManifestLaunch(
    val argv: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

@Serializable internal data class ManifestEntries(val file: Long? = null)

@Serializable internal data class ManifestDirectEngine(val schema: Int? = null)
