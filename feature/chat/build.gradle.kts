plugins {
    id("com.android.library")
    id("com.ncorti.ktfmt.gradle")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

ktfmt { kotlinLangStyle() }

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

android {
    namespace = "dev.fughetta.dshcompose.feature.chat"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    testImplementation(testFixtures(project(":core:platform")))
    testImplementation(platform("io.ktor:ktor-bom:3.6.1"))
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation("io.ktor:ktor-server-cio")
    testImplementation("io.ktor:ktor-server-websockets")
    implementation(project(":feature:providers"))
    implementation(project(":feature:files"))
    api(project(":feature:shell:api"))
    implementation(project(":data"))
    implementation(project(":data:protocol"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material3.adaptive:adaptive:1.4.0-alpha03")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("com.github.penfeizhou.android.animation:awebp:3.0.5")
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    implementation("io.coil-kt.coil3:coil-core:3.6.3")
    implementation("io.coil-kt.coil3:coil-compose-core:3.6.3")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("io.github.huarangmeng:latex-renderer:1.7.3")
    implementation("io.github.huarangmeng:latex-fonts-katex:1.7.3")
    implementation("io.github.dokar3:quickjs-kt:1.0.18")
    implementation("org.commonmark:commonmark:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.32.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.32.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.18")
}

apply(from = "build-logic/syntax.gradle.kts")

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(tasks.named("generateSyntax")) { task
            ->
            task.property("outputDirectory") as DirectoryProperty
        }
    }
}

configurations
    .matching { it.name.endsWith("UnitTestRuntimeClasspath") }
    .configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.dokar3:quickjs-kt-android"))
                .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.18"))
        }
    }
