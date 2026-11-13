pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io")
    }
}

rootProject.name = "dsh-compose"
// UI and application assembly.
include(":app")

// Pure Kotlin models, repository contracts and business rules.
include(":domain")

// Data ownership, storage and engine protocol access.
include(
    ":data",
    ":data:local",
    ":data:protocol",
    ":data:engine",
)
