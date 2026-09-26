@file:Suppress("UnstableApiUsage")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "dev.rikka.tools.autoresconfig" ->
                    useModule("com.github.LSPosed.AutoResConfig:gradle-plugin:${requested.version}")
                "dev.rikka.tools.materialthemebuilder" ->
                    useModule("com.github.LSPosed.MaterialThemeBuilder:gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

// No spaces: Gradle rejects a project name that is not [a-zA-Z]([A-Za-z0-9\-_])*.
// The name modules actually see comes from FRAMEWORK_NAME in core/build.gradle.kts.
rootProject.name = "Lsposed-IrenaXNext"
include(
    ":app",
    ":core",
    ":daemon",
    ":dex2oat",
    ":hiddenapi:stubs",
    ":hiddenapi:bridge",
    ":magisk-loader",
    ":manager-ui",
    ":libxposed:api",
    ":libxposed:service",
    ":libxposed:compat",
    ":services:manager-service",
    ":services:daemon-service",
)
