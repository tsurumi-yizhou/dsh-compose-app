package dev.fughetta.dshcompose.engineui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.fughetta.dshcompose.diagnostics.Diagnostics
import dev.fughetta.dshcompose.diagnostics.asJsonElement
import dev.fughetta.dshcompose.domain.notify.NotificationPrivacy
import dev.fughetta.dshcompose.engine.runtime.RuntimeController
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Only sanitized, allowlisted content reaches either preview or an archive. */
internal object EngineDiagnostics {
    private val assignment =
        Regex(
            "(?im)([A-Za-z0-9_.-]*(?:key|token|secret|password|cookie|authorization)[A-Za-z0-9_.-]*[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)",
        )
    private val credentialHeader = Regex("(?im)^\\s*(?:cookie|set-cookie|authorization)\\s*:.*$")
    private val privateKey =
        Regex(
            "(?s)-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?(?:-----END [^-\\r\\n]*PRIVATE KEY-----|\\z)",
        )
    private val ansi = Regex("\\u001b\\[[0-?]*[ -/]*[@-~]")

    fun clean(raw: String): String =
        credentialHeader
            .replace(
                privateKey.replace(raw, "[private key redacted]"),
                "[credential header redacted]",
            )
            .lineSequence()
            .take(10_000)
            .joinToString("\n") { line ->
                assignment
                    .replace(NotificationPrivacy.mask(ansi.replace(line.take(16_384), ""))) {
                        "${it.groupValues[1]}[redacted]"
                    }
                    .take(2000)
            }

    fun collect(context: Context, runtime: RuntimeController, extra: Map<String, Any?>): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val memory =
            ActivityManager.MemoryInfo().also {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getMemoryInfo(it)
            }
        val status = runtime.status()
        val keys =
            setOf(
                "phase",
                "abi",
                "bundledVersion",
                "installedVersion",
                "dshVersion",
                "nodeVersion",
                "mode",
                "strikes",
                "error",
                "currentExecutionMode",
                "launchSelectedMode",
                "launchSpawned",
                "launchFailureReason",
                "networkBlocked",
            )
        // Extra facts must be deliberately picked by the caller, never a connection/status object.
        val facts =
            mapOf(
                "app" to mapOf("version" to info.versionName),
                "device" to
                    mapOf(
                        "manufacturer" to Build.MANUFACTURER,
                        "model" to Build.MODEL,
                        "sdk" to Build.VERSION.SDK_INT,
                        "memoryBytes" to memory.totalMem,
                        "availableMemoryBytes" to memory.availMem,
                        "freeBytes" to context.filesDir.usableSpace,
                    ),
                "runtime" to status.filterKeys { it in keys },
                "manifest" to
                    runtime.manifest.let { m ->
                        mapOf(
                                "runtimeVersion" to m.runtimeVersion.ifEmpty { null },
                                "abi" to m.abi.ifEmpty { null },
                                "dshVersion" to m.dshVersion.ifEmpty { null },
                                "nodeVersion" to m.nodeVersion.ifEmpty { null },
                                "rootfsBytes" to m.rootfsBytes.takeIf { it >= 0 },
                                "uncompressedBytes" to m.uncompressedBytes.takeIf { it >= 0 },
                                "files" to m.files,
                            )
                            .filterValues { it != null }
                    },
                "extra" to extra,
            )
        val logs =
            listOf(
                    File(context.filesDir, "runtime/logs/dsh.log.1"),
                    File(context.filesDir, "runtime/logs/dsh.log"),
                )
                .flatMap { Diagnostics.tail(it, 1024 * 1024) }
                .takeLast(6000)
                .joinToString("\n")
        val pretty = Json {
            prettyPrint = true
        }
            .encodeToString(JsonElement.serializer(), facts.asJsonElement())
        return "# DSH-Compose diagnostics\n\n${clean(pretty)}\n\n## Engine log\n\n${clean(logs)}\n\n## Failure log\n\n${clean(runtime.failureLog().takeLast(1000).joinToString("\n"))}\n"
    }

    fun archive(context: Context, reviewed: String, revision: Long): File {
        val dir = File(context.cacheDir, "artifacts/diagnostics").apply { mkdirs() }
        dir.listFiles()
            ?.filter {
                it.name.startsWith("diagnostics-") &&
                    System.currentTimeMillis() - it.lastModified() > 7 * 24 * 60 * 60 * 1000L
            }
            ?.forEach { it.delete() }
        return File(dir, "diagnostics-$revision.zip").also { target ->
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.md"))
                zip.write(reviewed.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
