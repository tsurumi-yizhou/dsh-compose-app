plugins {
    id("com.android.library") version "9.5.1"
    id("com.ncorti.ktfmt.gradle") version "0.28.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

ktfmt { kotlinLangStyle() }

tasks.withType<org.gradle.api.tasks.SourceTask>().configureEach {
    if (name.endsWith("Scripts")) {
        setSource(fileTree(projectDir) { include("*.gradle.kts", "build-logic/*.gradle.kts") })
    }
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

android {
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    namespace = "dev.fughetta.dshcompose.engine"

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi" }
        create("x64") { dimension = "abi" }
    }

    androidResources { noCompress += listOf("gz", "xz", "zst", "tar", "tgz") }
}

apply(from = "build-logic/runtime.gradle.kts")

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(tasks.named("buildEnginePackage")) {
            task ->
            task.property("assetsDirectory") as DirectoryProperty
        }
        val flavor = variant.productFlavors.single { it.first == "abi" }.second
        val runtime = tasks.named("build${flavor.replaceFirstChar(Char::uppercase)}Runtime")
        variant.sources.assets?.addGeneratedSourceDirectory(runtime) { task ->
            task.property("assetsDirectory") as DirectoryProperty
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(runtime) { task ->
            task.property("jniDirectory") as DirectoryProperty
        }
    }
}

dependencies {
    api(project(":domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.tukaani:xz:1.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
}

// Exercise bundled skill installation using the same generated guide consumed by the APK.
tasks.withType<Test>().configureEach {
    val guide = project(":app").tasks.named("generateGuide")
    val assets = rootProject.layout.buildDirectory.dir("generated/guideAssets")
    dependsOn(guide, "buildEnginePackage")
    inputs.dir(assets)
    inputs.dir(rootProject.file("app/src/main/assets/skills"))
    inputs.dir(layout.buildDirectory.dir("generated/engineAssets/skills"))
    systemProperty(
        "dsh.test.hostAssets",
        layout.buildDirectory.dir("generated/engineAssets").get().asFile.absolutePath,
    )
    systemProperty("dsh.test.guideAssets", assets.get().asFile.absolutePath)
    systemProperty(
        "dsh.test.skillAssets",
        rootProject.file("app/src/main/assets/skills").absolutePath,
    )
}
