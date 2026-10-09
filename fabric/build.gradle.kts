// Minecraft-facing module: wires the pure-Kotlin :core into Fabric. Built once per Minecraft version
// (:fabric:<version>, see stonecutter.gradle.kts) from the shared fabric/src.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dev.kikugie.loom-back-compat") // fabric-loom-remap on 1.21.x, fabric-loom on 26.1+
    id("org.jetbrains.kotlin.jvm")
}

val mc: String = sc.current.version
val modVersion = property("mod_version") as String
val javaVersion = sc.properties.get<Long>("mod.java").toInt()
val fabricDir: File = rootProject.file("fabric") // shared sources and run folders, whatever the version
fun dep(key: String): String = sc.properties.get<String>(key) // a stonecutter.properties.toml value

version = "$modVersion+$mc"
group = property("maven_group") as String
base { archivesName = property("archives_base_name") as String }

repositories {
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    maven("https://jitpack.io") { name = "JitPack" }
    maven("https://maven.wispforest.io/releases/") { name = "wispforest" }
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    loomx.applyMojangMappings() // Mojang's names everywhere; a no-op on unobfuscated versions
    modImplementation("net.fabricmc:fabric-loader:${dep("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${dep("deps.fabric_api")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${dep("deps.fabric_kotlin")}")

    // owo-ui: screens are XML models under assets/jukz/owo_ui, hot-reloaded in dev (see JukzUiScreen).
    // Players install owo-lib like fabric-api; the tiny sentinel JiJ explains it if it is missing.
    modImplementation("io.wispforest:owo-lib:${dep("deps.owo")}")
    include("io.wispforest:owo-sentinel:${dep("deps.owo")}")

    // Mod Menu: compile-only, so jukz gets its Config button and links there but never requires it.
    modCompileOnly("com.terraformersmc:modmenu:${dep("deps.modmenu")}")

    // The deterministic core. include() jar-in-jars it so it ships inside the mod.
    implementation(project(":core"))
    include(project(":core"))
    // pane: Chromium off-screen, for the in-game browser (client/web). jar-in-jar like core.
    implementation("com.github.Nuulz:pane:v0.1.0")
    include("com.github.Nuulz:pane:v0.1.0")

    // Coroutines for the client join coordinator (runBlocking). fabric-language-kotlin ships the
    // same artifact at runtime; declaring it keeps it on the compile classpath.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutines_version")}")

    // JGit world-sync (Maven Central, EDL-1.0, Java 17+), jar-in-jar'd so a normal install has it. Of
    // its dependencies only JavaEWAH is missing from the game: Minecraft already ships slf4j and
    // commons-codec.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    include("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    include("com.googlecode.javaewah:JavaEWAH:1.2.3")

    // xz (pure Java, no natives): world snapshots are compressed as one stream, see SnapshotCodec.
    implementation("org.tukaani:xz:1.10")
    include("org.tukaani:xz:1.10")

    // ---- FLAGGED network libs (NAT traversal, requires live-network testing + shading) ----
    // MC ships its own Netty; ice4j + netty-codec-native-quic MUST be shaded/relocated to avoid
    // classpath clashes. Left out of the build; the adapters (IceTransport / HolePuncher) document the
    // exact calls and are wired behind core interfaces, so enabling these is additive.
    // implementation("org.jitsi:ice4j:3.2-15-g6da2b08")                  // STUN/ICE/TURN + UPnP
    // implementation("io.netty:netty-codec-native-quic:4.2.9.Final")    // reliable UDP tunnel

    // ---- Tests (JUnit5; Loom keeps Minecraft on the test classpath for sidecar/NBT round-trips) ----
    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutines_version")}")
}

loom {
    // Single source set; client-only classes are guarded with @Environment(CLIENT).

    // Two isolated client run configs for testing jukz host <-> guest on ONE machine. Each gets its own
    // run folder under fabric/run (separate logs / saves / config / jukz.nodeid -> distinct peers) and a
    // fixed username so the two windows are easy to tell apart in logs.
    // Launch: gradlew :fabric:1.21.1:runClientA / runClientB (or run-client-a.bat / run-client-b.bat).
    runs {
        // Dev runs point owo-ui's hot reload at the XML in src/, so saving a screen's model redraws it
        // in-game immediately (JukzUiScreen watches the file). Never set outside dev runs.
        configureEach {
            vmArg("-Djukz.uiSourceDir=${fabricDir.resolve("src/main/resources/assets/jukz/owo_ui").absolutePath}")
        }
        register("clientA") {
            inherit(getByName("client"))
            configName = "Client A (jukz host)"
            runDir = fabricDir.resolve("run/clientA").relativeTo(projectDir).path
            programArgs("--username", "HostA")
        }
        register("clientB") {
            inherit(getByName("client"))
            configName = "Client B (jukz guest)"
            runDir = fabricDir.resolve("run/clientB").relativeTo(projectDir).path
            programArgs("--username", "GuestB")
        }
        // For playing from a checkout (see ../jugar): game folder (under fabric/), name and memory
        // come from -P flags.
        //   gradlew :fabric:1.21.1:runPlay -Pjukz.runDir=run/nob -Pjukz.username=Nob -Pjukz.ram=4G
        register("play") {
            inherit(getByName("client"))
            configName = "Play (jukz)"
            runDir = fabricDir.resolve((findProperty("jukz.runDir") ?: "run/play").toString()).relativeTo(projectDir).path
            programArgs("--username", (findProperty("jukz.username") ?: "Player").toString())
            vmArg("-Xmx${findProperty("jukz.ram") ?: "4G"}")
        }
    }
}

// In-game previews of cosmetics (client gametest, 1.21.11+ only): a real client films the items and
// cosmetics/tools/preview-in-game.sh turns the screenshots into a GIF. Run it through that script.
if (mc != "1.21.1") {
    fabricApi {
        configureTests {
            createSourceSet = true
            modId = "jukz-preview"
            enableGameTests = false
            enableClientGameTests = true
            eula = true
        }
    }
    tasks.matching { it.name == "runClientGameTest" }.configureEach {
        val exec = this as JavaExec
        for (key in listOf("jukz.preview.items", "jukz.preview.out", "jukz.preview.frames", "jukz.preview.moves")) {
            findProperty(key)?.let { exec.systemProperty(key, it.toString()) }
        }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
    withSourcesJar()
}

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions { jvmTarget = JvmTarget.fromTarget(javaVersion.toString()) }
}

tasks.test {
    workingDir = fabricDir // tests read src/ and ../gradle.properties relative to fabric/
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
}

tasks.processResources {
    val props = mapOf(
        "version" to modVersion,
        "minecraft" to sc.properties.get<String>("mod.mc_compat"),
        "java" to "JAVA_$javaVersion",
        "java_major" to javaVersion.toString(),
        "loader" to dep("deps.fabric_loader"),
        "owo_min" to dep("mod.owo_min"),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
    filesMatching("jukz.mixins.json") { expand(props) }
    // One catalog for the Worker and the mod (the live copy replaces this one once fetched).
    from(rootProject.file("cosmetics/catalog.json")) { into("assets/jukz/cosmetics") }
    // What's new, for the "jukz was updated" screen.
    from(rootProject.file("CHANGELOG.md")) { into("assets/jukz") }
}
