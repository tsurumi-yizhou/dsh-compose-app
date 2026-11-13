import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Builds the base Linux tools consumed by Android; engine packages are assembled separately. */
@DisableCachingByDefault(because = "Runtime assembly uses local download caches and platform tools")
abstract class BuildRuntime
@Inject
constructor(private val processes: ExecOperations, private val files: FileSystemOperations) :
    DefaultTask() {
    @get:Input abstract val abi: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val runtimeDirectory: DirectoryProperty
    @get:Internal abstract val outputDirectory: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @get:OutputDirectory abstract val jniDirectory: DirectoryProperty

    init {
        assetsDirectory.convention(outputDirectory.dir("assets"))
        jniDirectory.convention(outputDirectory.dir("jniLibs"))
    }

    @TaskAction
    fun build() {
        processes.exec {
            workingDir(runtimeDirectory.get().asFile)
            commandLine(
                "bash",
                runtimeDirectory.file("build-runtime.sh").get().asFile,
                abi.get(),
            )
            environment("RUNTIME_OUT", outputDirectory.get().asFile.parentFile.absolutePath)
        }
        for ((name, target) in listOf("assets" to assetsDirectory, "jniLibs" to jniDirectory)) {
            val source = outputDirectory.dir(name).get().asFile
            if (source != target.get().asFile)
                files.sync {
                    from(source)
                    into(target)
                }
        }
    }
}

val runtimeSources =
    fileTree("runtime") {
        include(
            "build-runtime.sh",
            "lib/**",
            "boot/**",
            "direct/**",
            "locks/pnpm-12.18.0/package-lock.json",
        )
        exclude("**/__pycache__/**")
    }

mapOf("arm64" to "arm64-v8a", "x64" to "x86_64").forEach { (flavor, architecture) ->
    tasks.register<BuildRuntime>("build${flavor.replaceFirstChar(Char::uppercase)}Runtime") {
        group = "build"
        description = "Build the bundled engine runtime for $architecture."
        abi.set(architecture)
        sources.from(runtimeSources)
        runtimeDirectory.set(layout.projectDirectory.dir("runtime"))
        outputDirectory.set(layout.projectDirectory.dir("runtime/out/$architecture"))
    }
}

@DisableCachingByDefault(because = "Packages the published host with Android installation scripts")
abstract class BuildEngine @Inject constructor(private val processes: ExecOperations) :
    DefaultTask() {
    @get:Input abstract val engineVersion: Property<String>
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hostPackage: RegularFileProperty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val runtimeDirectory: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        processes.exec {
            workingDir(runtimeDirectory.get().asFile)
            commandLine(
                "bash",
                runtimeDirectory.file("build-engine.sh").get().asFile,
                assetsDirectory.get().asFile,
                engineVersion.get(),
                hostPackage.get().asFile,
            )
        }
    }
}

tasks.register<BuildEngine>("buildEnginePackage") {
    group = "build"
    description = "Package the host artifact for installation during app setup."
    engineVersion.set(
        providers.gradleProperty("versionCode").orElse(providers.gradleProperty("appVersionCode")),
    )
    hostPackage.set(
        rootProject.layout.file(
            providers.gradleProperty("hostPackage").map { rootProject.file(it) },
        ),
    )
    runtimeDirectory.set(layout.projectDirectory.dir("runtime"))
    assetsDirectory.set(layout.buildDirectory.dir("generated/engineAssets"))
    sources.from(
        fileTree("runtime") {
            include(
                "build-engine.sh",
                "setup/**",
                "locks/pnpm-12.18.0/**",
            )
        },
    )
}
