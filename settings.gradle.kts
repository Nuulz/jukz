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
        versions("1.21.1", "1.21.11", "26.2")
        vcsVersion = "1.21.1"
    }
}

// The in-game browser engine (github.com/Nuulz/pane). With a checkout next to this one, it builds from
// there (edit both at once); otherwise JitPack's build of the version in fabric/build.gradle.kts.
if (file("../pane").isDirectory) {
    includeBuild("../pane") {
        dependencySubstitution { substitute(module("com.github.Nuulz:pane")).using(project(":")) }
    }
}
