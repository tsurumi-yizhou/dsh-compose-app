plugins {
    id("com.android.library") version "9.5.1"
    id("com.ncorti.ktfmt.gradle") version "0.28.0"
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

    namespace = "dev.fughetta.dshcompose.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    api(project(":domain"))
    implementation(project(":data:protocol"))
    implementation(project(":data:local"))
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-encoding")
    implementation("org.snakeyaml:snakeyaml-engine:3.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation("io.ktor:ktor-server-cio")
    testImplementation("io.ktor:ktor-server-websockets")
}
