plugins {
    id("com.android.library")
    id("com.ncorti.ktfmt.gradle")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

ktfmt { kotlinLangStyle() }

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

android {
    namespace = "dev.fughetta.dshcompose.feature.phone"
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
    implementation("io.ktor:ktor-http:3.6.1")

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
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.87")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.18")
}
