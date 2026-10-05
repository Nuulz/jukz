pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    // One source tree, one jar per Minecraft version: see fabric/stonecutter.gradle.kts.
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Picks the Loom variant per version: remapping for 1.21.x, none for 26.1+ (unobfuscated).
    id("dev.kikugie.loom-back-compat") version "0.4.3"
    // Lets Gradle download the JDK a version needs (21 for 1.21.x, 25 for 26.x).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jukz"

include("core")
include("fabric")

stonecutter {
    create(":fabric") {
        versions("1.21.1")
        vcsVersion = "1.21.1"
    }
}
