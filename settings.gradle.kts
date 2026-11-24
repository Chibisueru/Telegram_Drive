pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads a vanilla JDK for packaging the desktop app (distro JDKs often can't run jlink).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TelegramDrive"
// CI's desktop jobs skip the Android app (they have no Android SDK set up for it).
if (providers.gradleProperty("desktopOnly").orNull != "true") include(":app")
include(":desktop")
