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
    api(project(":domain"))
    api(platform("io.ktor:ktor-bom:3.6.1"))
    api("io.ktor:ktor-client-core")
    api("io.ktor:ktor-client-websockets")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-http-cio")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation("io.ktor:ktor-server-cio")
    testImplementation("io.ktor:ktor-server-websockets")
}
