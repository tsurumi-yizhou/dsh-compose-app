import com.android.build.api.artifact.SingleArtifact
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Properties

plugins {
    id("com.android.application") version "9.5.1"
    id("com.ncorti.ktfmt.gradle") version "0.28.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

fun buildVersionCode(at: OffsetDateTime): Int {
    val time = at.withOffsetSameInstant(ZoneOffset.ofHours(8))
    val value =
        (time.year.toLong() - 2026) * 100_000_000 +
            time.monthValue * 1_000_000 +
            time.dayOfMonth * 10_000 +
            time.hour * 100 +
            time.minute
    require(time.year >= 2026 && value in 1..2_100_000_000L) {
        "Build time is outside Android's versionCode range"
    }
    return value.toInt()
}

val keyProps =
    Properties().apply {
        project.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        for ((propertyName, environmentName) in
            mapOf(
                "storeFile" to "DSH_SIGNING_STORE_FILE",
                "storePassword" to "DSH_SIGNING_STORE_PASSWORD",
                "keyAlias" to "DSH_SIGNING_KEY_ALIAS",
                "keyPassword" to "DSH_SIGNING_KEY_PASSWORD",
            )) {
            providers.environmentVariable(environmentName).orNull?.let {
                setProperty(propertyName, it)
            }
        }
    }

// Debug builds use the release key only when it is all there; they are never the build
// that should stop over a half-filled key.properties.
// A password may be nothing but spaces; a path or an alias may not.
val hasWholeReleaseKey =
    listOf("storeFile", "keyAlias").all { !keyProps.getProperty(it).isNullOrBlank() } &&
        listOf("storePassword", "keyPassword").all { !keyProps.getProperty(it).isNullOrEmpty() } &&
        file(keyProps.getProperty("storeFile")).exists()

if (providers.gradleProperty("requireReleaseSigning").orNull.toBoolean()) {
    require(hasWholeReleaseKey) {
        "Release signing is required; configure the complete signing key."
    }
}

val legalAssets = layout.buildDirectory.dir("generated/legalAssets")
val syncLegalAssets =
    tasks.register<Copy>("syncLegalAssets") {
        from(rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("LICENSE.md")) { rename { "LICENSE" } }
        into(legalAssets.map { it.dir("licenses") })
    }

tasks.named("preBuild").configure { dependsOn(syncLegalAssets) }

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

    namespace = "dev.fughetta.dshcompose"
    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.fughetta.dshcompose"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode =
            providers
                .gradleProperty("versionCode")
                .map(String::toInt)
                .orElse(
                    providers.gradleProperty("releaseBuildTime").map {
                        buildVersionCode(OffsetDateTime.parse(it))
                    },
                )
                .orElse(providers.gradleProperty("appVersionCode").map(String::toInt))
                .get()
        versionName =
            providers
                .gradleProperty("versionName")
                .orElse(providers.gradleProperty("appVersion"))
                .get()
        // -PstarPrompt=true for public builds.
        buildConfigField(
            "boolean",
            "STAR_PROMPT",
            providers.gradleProperty("starPrompt").orNull.toBoolean().toString(),
        )
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(legalAssets.get().asFile.absolutePath)
            java.directories.clear()
        }
    }

    packaging {
        // proot and friends ship as lib*.so; they must be extracted to
        // nativeLibraryDir so they can be executed.
        jniLibs {
            useLegacyPackaging = true
            // Keep the Termux binaries byte-identical to data/engine/runtime/out (their hashes are
            // in
            // the
            // manifest).
            keepDebugSymbols +=
                listOf(
                    "**/libproot.so",
                    "**/libproot-loader.so",
                    "**/libtalloc.so",
                    "**/libandroid-shmem.so",
                )
        }
    }

    androidResources { noCompress += listOf("gz", "xz", "zst", "tar") }

    signingConfigs {
        if (hasWholeReleaseKey) {
            create("release") {
                storeFile = keyProps.getProperty("storeFile")?.let { file(it) }
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
                // All three schemes: AGP drops v1 for minSdk 24+, but some installers and
                // security scanners on Chinese ROMs still read the JAR signature.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug { if (hasWholeReleaseKey) signingConfig = signingConfigs.getByName("release") }
        release {
            // Unsigned local release builds stay unsigned. Distribution requires the release key.
            signingConfig = signingConfigs.findByName("release")
            // Phone control's privileged process and TLS libraries are reached by class name.
            proguardFiles("proguard-rules.pro")
        }
    }
}

apply(from = "build-logic/guide.gradle.kts")

apply(from = "build-logic/delta.gradle.kts")

apply(from = "build-logic/release.gradle.kts")

@Suppress("UNCHECKED_CAST")
val registerRelease = extra["registerRelease"] as Action<Pair<String, Provider<Directory>>>
@Suppress("UNCHECKED_CAST")
val deltaApk = extra["deltaApk"] as Action<Pair<String, Provider<Directory>>>

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(tasks.named("generateGuide")) { task ->
            task.property("outputDirectory") as DirectoryProperty
        }
        val flavorName = variant.productFlavors.single { it.first == "abi" }.second
        val apk = variant.artifacts.get(SingleArtifact.APK)
        if (variant.buildType == "release") registerRelease.execute(flavorName to apk)
        if (flavorName == "arm64") deltaApk.execute(requireNotNull(variant.buildType) to apk)
    }
}

dependencies {
    testImplementation(testFixtures(project(":core:platform")))
    implementation(project(":core:phone"))
    implementation(project(":core:platform"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:shell:api"))
    implementation(project(":feature:phone"))
    implementation(project(":feature:providers"))
    implementation(project(":feature:engine"))
    implementation(project(":feature:files"))
    implementation(project(":feature:chat"))
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(project(":domain"))
    implementation(project(":data"))
    // The composition root wires endpoint callbacks whose signatures use ProtocolClient.
    implementation(project(":data:protocol"))
    implementation(project(":data:engine"))
    implementation("com.github.penfeizhou.android.animation:awebp:3.0.5")
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    implementation("io.coil-kt.coil3:coil-core:3.6.3")
    implementation(platform("io.ktor:ktor-bom:3.6.1"))
    implementation("io.ktor:ktor-http")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material3.adaptive:adaptive:1.4.0-alpha03")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation3:1.4.0-alpha03")
    implementation("androidx.navigation3:navigation3-runtime:1.3.0-alpha04")
    implementation("androidx.navigation3:navigation3-ui:1.3.0-alpha04")
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.coil-kt.coil3:coil-compose-core:3.6.3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation("io.ktor:ktor-server-cio")
    testImplementation("io.ktor:ktor-server-websockets")
}
