plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("java-library")
    id("com.ncorti.ktfmt.gradle") version "0.28.0"
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

ktfmt { kotlinLangStyle() }

tasks.withType<org.gradle.api.tasks.SourceTask>().configureEach {
    if (name.endsWith("Scripts")) {
        setSource(fileTree(projectDir) { include("*.gradle.kts", "build-logic/*.gradle.kts") })
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    testImplementation("junit:junit:4.13.2")
}
