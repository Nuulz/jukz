package dev.jukz.client.gui

import dev.jukz.JukzMod
import io.wispforest.owo.Owo
import io.wispforest.owo.ui.base.BaseUIModelScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.parsing.UIModelLoader
import net.minecraft.client.gui.screen.Screen
import net.minecraft.util.Identifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Base for jukz screens whose layout + style is an owo-ui XML model at
 * `assets/jukz/owo_ui/<modelName>.xml`; the subclass only fills in data and wires ids in `build`.
 *
 * In a dev run the model is read straight from `src/` (see [UiHotReload]) and this screen watches the
 * file: saving it rebuilds the open screen via [recreate], so layout work is edit → save → look, with
 * no restart. In a release build none of that is active and the model comes from the jar.
 */
abstract class JukzUiScreen(modelName: String) :
    BaseUIModelScreen<FlowLayout>(FlowLayout::class.java, DataSource.asset(Identifier.of("jukz", modelName))) {

    private val watchedSource: Path? = UiHotReload.sourceFor(modelName)
    private var lastModified = watchedSource?.let(::modifiedAt)

    /** A fresh instance of this screen (same inputs), used to redraw it after its model is edited. */
    protected abstract fun recreate(): Screen

    override fun tick() {
        super.tick()
        val source = watchedSource ?: return
        val modified = modifiedAt(source)
        if (modified != lastModified) {
            lastModified = modified
            client?.setScreen(recreate())
        }
    }

    private fun modifiedAt(path: Path): Long = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
}

/**
 * Dev-only wiring for owo-ui hot reload. The Loom dev runs pass `-Djukz.uiSourceDir` (the `owo_ui`
 * folder in `src/`); when owo's debug mode is on (the default in a dev environment) every jukz model
 * there is registered as its own hot-reload location, so owo reads the live file instead of the copy
 * baked into the resources. Outside a dev run the property is absent and this does nothing.
 */
object UiHotReload {
    private val sourceDir: Path? = System.getProperty("jukz.uiSourceDir")
        ?.let { Path.of(it) }
        ?.takeIf { Owo.DEBUG && Files.isDirectory(it) }

    fun install() {
        val dir = sourceDir ?: return
        val models = Files.list(dir).use { files -> files.filter { it.extension == "xml" }.toList() }
        models.forEach { UIModelLoader.setHotReloadPath(Identifier.of("jukz", it.nameWithoutExtension), it) }
        JukzMod.logger.info("jukz: owo-ui hot reload on for {} model(s) in {}", models.size, dir)
    }

    fun sourceFor(modelName: String): Path? = sourceDir?.resolve("$modelName.xml")?.takeIf { Files.exists(it) }
}
