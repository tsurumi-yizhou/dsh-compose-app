plugins {
    id("com.android.library")
    id("com.ncorti.ktfmt.gradle")
    id("org.jetbrains.kotlin.plugin.serialization")
}

ktfmt { kotlinLangStyle() }

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

android {
    testFixtures { enable = true }
    namespace = "dev.fughetta.dshcompose.core.platform"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testFixturesImplementation(project(":data:protocol"))
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("androidx.activity:activity:1.13.0")

    api(project(":domain"))
    implementation(project(":data"))
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation("androidx.core:core-ktx:1.18.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.18")
}
