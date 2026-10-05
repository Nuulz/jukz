// Stonecutter controller for :fabric. Each Minecraft version is a subproject (:fabric:<version>)
// built from the same src/, with its own dependency versions in stonecutter.properties.toml.
// `//? if >=1.21.11 { ... }` comments in the sources pick code per version; "Set active project to …"
// rewrites src/ for the version you are editing (the committed state is 1.21.1).
plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.1"
