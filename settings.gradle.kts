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

// Shared infrastructure, feature contracts and feature implementations.
include(
    ":core:platform",
    ":core:designsystem",
    ":core:phone",
    ":feature:phone:api",
    ":feature:providers:api",
    ":feature:engine:api",
    ":feature:files:api",
    ":feature:chat:api",
    ":feature:shell:api",
    ":feature:phone",
    ":feature:providers",
    ":feature:engine",
    ":feature:files",
    ":feature:chat",
    ":feature:accessibility",
)
