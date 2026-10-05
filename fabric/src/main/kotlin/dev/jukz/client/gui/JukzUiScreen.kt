package dev.jukz.client.gui

import dev.jukz.JukzMod
import io.wispforest.owo.Owo
import io.wispforest.owo.ui.base.BaseUIModelScreen
import io.wispforest.owo.ui.component.BoxComponent
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Color
import dev.jukz.compat.UIComponent
import io.wispforest.owo.ui.core.OwoUIAdapter
import dev.jukz.compat.ParentUIComponent
import io.wispforest.owo.ui.core.Positioning
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.parsing.UIModel
import io.wispforest.owo.ui.parsing.UIModelLoader
import dev.jukz.compat.GuiGraphics
import net.minecraft.network.chat.Component
import dev.jukz.compat.Identifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Base for every jukz screen. Layout and style are an owo-ui XML model at
 * `assets/jukz/owo_ui/<modelName>.xml`, built from the shared templates in `theme.xml`; the subclass
 * only fills in data and wires components by id in [build].
 *
 * In a dev run the models are read straight from `src/` (see [UiHotReload]) and the screen watches its
 * own model and the theme: saving either rebuilds the open screen in place, keeping its state, so layout
 * work is edit → save → look with no restart. If the edited model fails to build, it falls back to the
 * packaged one (owo reports the error). In a release build none of that is active.
 */
abstract class JukzUiScreen(modelName: String) :
    BaseUIModelScreen<FlowLayout>(FlowLayout::class.java, DataSource.asset(Identifier.fromNamespaceAndPath("jukz", modelName))) {

    private val modelId = Identifier.fromNamespaceAndPath("jukz", modelName)

    /** The model the current UI was built from. BaseUIModelScreen's `model` is fixed at construction. */
    protected var ui: UIModel? = model
        private set

    private val watched = listOfNotNull(UiHotReload.sourceFor(modelName), UiHotReload.sourceFor(THEME))
    private var stamps = watched.map(::modifiedAt)

    /**
     * The screen model only declares the panel's content; the frame around it (backdrop + panel) is
     * theme.xml's "screen" template. They are joined here because owo can't pass child elements into a
     * template from another model file (the DOM nodes belong to different documents).
     */
    override fun createAdapter(): OwoUIAdapter<FlowLayout> {
        val content = ui!!.createAdapterWithoutScreen(0, 0, 0, 0, FlowLayout::class.java).rootComponent
        content.sizing(Sizing.content(), Sizing.content())
        val frame = themed(FlowLayout::class.java, "screen")
        frame.childById(FlowLayout::class.java, "panel").child(content)
        frame.sizing(Sizing.fill(100), Sizing.fill(100))
        return OwoUIAdapter.create(this) { _, _ -> frame }
    }

    override fun tick() {
        super.tick()
        if (watched.isEmpty()) return
        val now = watched.map(::modifiedAt)
        if (now != stamps) {
            stamps = now
            rebuild()
        }
    }

    /** Throw the current UI away and build it again from the (re-read) model; [build] runs again. */
    protected fun rebuild() {
        swapModel(UIModelLoader.get(modelId) ?: return)
        if (invalid) {
            // The edited model (or theme) didn't build — fall back to the packaged copy.
            invalid = false
            UIModelLoader.getPreloaded(modelId)?.let(::swapModel)
        }
    }

    private fun swapModel(next: UIModel) {
        ui = next
        uiAdapter?.dispose()
        uiAdapter = null
        rebuildWidgets()
    }

    /**
     * Vanilla's own backdrop — the menu panorama, or the blurred world in-game — behind the panel.
     * owo's base screen turns it off in favour of component surfaces; jukz keeps the familiar look.
     */
    //? if >=26.2 {
    /*override fun extractBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        if (minecraft?.level == null) extractPanorama(context, delta)
        extractBlurredBackground(context)
        extractMenuBackground(context)
    }
    *///?} else {
    override fun renderBackground(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        if (minecraft?.level == null) renderPanorama(context, delta)
        //? if >=1.21.11 {
        /*renderBlurredBackground(context)
        *///?} else {
        renderBlurredBackground(delta)
        //?}
        renderMenuBackground(context)
    }
    //?}

    // ---- shared building blocks (all from theme.xml) ---------------------------------------------

    /** A theme.xml "bar" in this screen's tree, by id. */
    protected fun progressBar(root: FlowLayout, id: String): ProgressBar = ProgressBar(
        root.childById(FlowLayout::class.java, id),
        root.childById(BoxComponent::class.java, "$id-fill"),
    )

    /**
     * Take [target] out of the tree under [root]. Works during [build], before mounting, when owo's
     * `Component.remove()` can't yet (components only learn their parent once mounted).
     */
    protected fun detach(root: ParentUIComponent, target: UIComponent): Boolean {
        for (child in root.children()) {
            if (child === target) {
                root.removeChild(child)
                return true
            }
            if (child is ParentUIComponent && detach(child, target)) return true
        }
        return false
    }

    /** Expand one of theme.xml's templates. */
    protected fun <T : UIComponent> themed(type: Class<T>, template: String, params: Map<String, String> = emptyMap()): T =
        ui!!.expandTemplate(type, "$template@jukz:$THEME", params)

    protected fun label(root: FlowLayout, id: String): LabelComponent = root.childById(LabelComponent::class.java, id)

    /** Tint the "jukz" brand: the accent says what the screen is about (working / your call / failed). */
    protected fun accent(root: FlowLayout, argb: Int) {
        root.childById(LabelComponent::class.java, "brand")?.color(Color.ofArgb(argb))
    }

    /** Append a themed button to the row with [rowId]. */
    protected fun addButton(root: FlowLayout, rowId: String, text: Component, width: Int = 150, onPress: () -> Unit): ButtonComponent {
        val button = themed(ButtonComponent::class.java, "button", mapOf("id" to "button-${text.string}", "width" to "$width"))
        button.message = text
        button.onPress { onPress() }
        root.childById(FlowLayout::class.java, rowId).child(button)
        return button
    }

    /** Wire a themed button the model already declares under [id]. */
    protected fun wireButton(root: FlowLayout, id: String, text: Component, onPress: () -> Unit): ButtonComponent =
        root.childById(ButtonComponent::class.java, id).apply {
            message = text
            onPress { onPress() }
        }

    private fun modifiedAt(path: Path): Long = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    companion object {
        private const val THEME = "theme"

        // Colours are full ARGB; a missing alpha byte renders transparent on 1.21.1.
        const val COLOR_TITLE = 0xFFFFFFFF.toInt()
        const val COLOR_SUBTLE = 0xFFB0B0B0.toInt()
        const val COLOR_LIVE = 0xFF6BCB6B.toInt()

        const val ACCENT_INFO = 0xFF7FB2FF.toInt() // searching / working
        const val ACCENT_CONNECT = 0xFF5B9BFF.toInt() // connecting
        const val ACCENT_ERROR = 0xFFFF6B6B.toInt() // failure
        const val ACCENT_ACTION = 0xFFFFC24A.toInt() // call to action (should-host)
    }
}

/** Drives a theme.xml "bar": call [indeterminate] or [progress] every frame, before drawing. */
class ProgressBar(val track: FlowLayout, private val fill: BoxComponent) {

    /** A 1.5 s ping-pong segment sliding along the track — the "working, not frozen" cue. */
    fun indeterminate(argb: Int) {
        val period = 1500L
        val t = (System.currentTimeMillis() % period) / period.toDouble()
        val pingPong = if (t < 0.5) t * 2 else 2 - t * 2 // 0 -> 1 -> 0
        val width = track.width().takeIf { it > 0 } ?: return
        val segment = (width * 0.29).toInt()
        show(segment, ((width - segment) * pingPong).toInt(), argb)
    }

    /** Fill the track left-to-right to [fraction] (0..1). */
    fun progress(fraction: Double, argb: Int) {
        show((track.width() * fraction.coerceIn(0.0, 1.0)).toInt(), 0, argb)
    }

    private fun show(width: Int, x: Int, argb: Int) {
        fill.horizontalSizing(Sizing.fixed(width))
        fill.positioning(Positioning.absolute(x, 0))
        fill.color(Color.ofArgb(argb))
    }
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
        models.forEach { UIModelLoader.setHotReloadPath(Identifier.fromNamespaceAndPath("jukz", it.nameWithoutExtension), it) }
        JukzMod.logger.info("jukz: owo-ui hot reload on for {} model(s) in {}", models.size, dir)
    }

    fun sourceFor(modelName: String): Path? = sourceDir?.resolve("$modelName.xml")?.takeIf { Files.exists(it) }
}
