plugins {
    id("com.google.devtools.ksp") version "2.3.14"
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
    namespace = "dev.fughetta.dshcompose.data.database"
}

dependencies {
    api("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.withType<Test>().configureEach {
    systemProperty(
        "dsh.test.roomSchemas",
        layout.projectDirectory.dir("schemas").asFile.absolutePath,
    )
}
