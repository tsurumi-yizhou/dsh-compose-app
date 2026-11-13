import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.function.Function
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies { classpath("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0") }
}

@DisableCachingByDefault(
    because = "Release metadata includes publication time and optional GitHub release history",
)
abstract class PrepareRelease : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Input abstract val flavor: Property<String>
    @get:Input abstract val repository: Property<String>
    @get:Input abstract val deltaFrom: Property<Int>
    @get:Input abstract val baseUrl: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notesFile: RegularFileProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @get:Internal lateinit var makeDelta: Action<List<File>>
    @get:Internal lateinit var digest: Function<File, String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun prepare() {
        require(deltaFrom.get() >= 0) { "deltaFrom must be non-negative" }
        val directory = apkDirectory.get().asFile
        val metadata =
            Json.parseToJsonElement(directory.resolve("output-metadata.json").readText()).jsonObject
        val elements = metadata.getValue("elements").jsonArray
        require(elements.size == 1) { "Expected one APK for ${flavor.get()}" }
        val element = elements.single().jsonObject
        val version = element.getValue("versionName").jsonPrimitive.content
        val code = element.getValue("versionCode").jsonPrimitive.int
        require(Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""").matches(version)) {
            "Invalid release version: $version"
        }
        val apk = directory.resolve(element.getValue("outputFile").jsonPrimitive.content)
        val expectedAbi = if (flavor.get() == "arm64") "arm64-v8a" else "x86_64"
        ZipFile(apk).use { zip ->
            val abis =
                zip.entries()
                    .asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .map { it.name.split('/')[1] }
                    .toSet()
            require(abis == setOf(expectedAbi)) {
                "${flavor.get()} APK contains $abis; expected $expectedAbi"
            }
        }
        val output = outputDirectory.get().asFile.apply { mkdirs() }
        val staging =
            temporaryDir.resolve("release").apply {
                deleteRecursively()
                mkdirs()
            }
        val name = "dsh-compose-$version-${flavor.get()}.apk"
        val target = apk.copyTo(staging.resolve(name), overwrite = true)
        val asset =
            linkedMapOf<String, Any>(
                "name" to name,
                "size" to target.length(),
                "sha256" to digest.apply(target),
            )
        if (baseUrl.get().isNotBlank()) asset["url"] = "${baseUrl.get().trimEnd('/')}/$name"
        val patches =
            if (flavor.get() == "arm64" && deltaFrom.get() > 0) patches(version, target, staging)
            else emptyList()
        val manifest =
            linkedMapOf<String, Any>(
                "schema" to 1,
                "version" to "$version+$code",
                "tag" to "v$version",
                "title" to "DSH Compose $version",
                "publishedAt" to Instant.now().toString(),
                "notes" to (notesFile.orNull?.asFile?.readText()?.trim() ?: ""),
                "page" to "https://github.com/${repository.get()}/releases/tag/v$version",
                "assets" to mapOf(flavor.get() to asset),
            )
        if (patches.isNotEmpty()) manifest["patches"] = mapOf("arm64" to patches)
        staging
            .resolve("update.json")
            .writeText(
                Json { prettyPrint = true }
                    .encodeToString(JsonElement.serializer(), json(manifest)) + "\n",
            )
        staging
            .resolve("SHA256SUMS")
            .writeText(
                (listOf(asset) + patches).joinToString("\n", postfix = "\n") {
                    "${it["sha256"]}  ${it["name"]}"
                },
            )
        output.deleteRecursively()
        staging.copyRecursively(output)
        logger.lifecycle("Release assets: $output")
    }

    private fun json(value: Any): JsonElement =
        when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> ->
                JsonObject(
                    value.entries.associate { (key, item) ->
                        key as String to json(requireNotNull(item))
                    },
                )
            is List<*> -> JsonArray(value.map { json(requireNotNull(it)) })
            else -> error("Unsupported release metadata value")
        }

    private fun gh(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(listOf("gh") + arguments + listOf("--repo", repository.get()))
            standardOutput = output
            errorOutput = error
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0)
            throw GradleException("gh release failed: ${error.toString(Charsets.UTF_8).trim()}")
        return output.toString(Charsets.UTF_8)
    }

    private fun patches(version: String, target: File, staging: File): List<Map<String, Any>> {
        val releases =
            Json.parseToJsonElement(
                    gh(
                        "release",
                        "list",
                        "--exclude-drafts",
                        "--limit",
                        "50",
                        "--json",
                        "tagName,publishedAt",
                    ),
                )
                .jsonArray
        return releases
            .map { it.jsonObject }
            .sortedByDescending { it.getValue("publishedAt").jsonPrimitive.content }
            .filter { it.getValue("tagName").jsonPrimitive.content != "v$version" }
            .take(deltaFrom.get())
            .mapNotNull { release ->
                val tag = release.getValue("tagName").jsonPrimitive.content
                val oldName = tag.removePrefix("v")
                require(Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""").matches(oldName)) {
                    "Invalid release tag: $tag"
                }
                val download = temporaryDir.resolve("previous/$tag").apply { mkdirs() }
                val apkName = "dsh-compose-$oldName-arm64.apk"
                gh(
                    "release",
                    "download",
                    tag,
                    "--pattern",
                    apkName,
                    "--pattern",
                    "update.json",
                    "--dir",
                    download.absolutePath,
                    "--clobber",
                )
                val oldManifest =
                    Json.parseToJsonElement(download.resolve("update.json").readText()).jsonObject
                val old = download.resolve(apkName)
                val patch = staging.resolve("dsh-compose-$version-from-$oldName-arm64.dshdelta")
                makeDelta.execute(listOf(old, target, patch))
                if (patch.length().toDouble() / target.length() >= 0.6) {
                    patch.delete()
                    null
                } else
                    linkedMapOf(
                        "from" to digest.apply(old),
                        "fromVersion" to (oldManifest.getValue("version").jsonPrimitive.content),
                        "name" to patch.name,
                        "size" to patch.length(),
                        "sha256" to digest.apply(patch),
                    )
            }
    }
}

@Suppress("UNCHECKED_CAST") val configuredDeltaMaker = extra["makeApkDelta"] as Action<List<File>>
@Suppress("UNCHECKED_CAST") val configuredDigest = extra["apkDigest"] as Function<File, String>

extra["registerRelease"] =
    Action<Pair<String, Provider<Directory>>> {
        val (flavorName, artifact) = this
        tasks.register<PrepareRelease>(
            "prepare${flavorName.replaceFirstChar { it.uppercase() }}Release",
        ) {
            group = "distribution"
            description = "Build the $flavorName release APK and prepare its release assets."
            apkDirectory.set(artifact)
            flavor.set(flavorName)
            repository.set(
                providers.gradleProperty("releaseRepository").orElse("ccch1mneyyy/dsh-compose"),
            )
            deltaFrom.set(providers.gradleProperty("deltaFrom").map(String::toInt).orElse(3))
            baseUrl.set(providers.gradleProperty("releaseBaseUrl").orElse(""))
            providers.gradleProperty("releaseNotesFile").orNull?.let {
                notesFile.set(rootProject.file(it))
            }
            outputDirectory.set(layout.buildDirectory.dir("release/$flavorName"))
            this.makeDelta = configuredDeltaMaker
            this.digest = configuredDigest
        }
    }
