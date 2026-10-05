// Names that moved between the Minecraft versions jukz builds for. The code is written with the newest
// names; on older versions these aliases point them at the old classes, so a file needs no per-version
// imports. Stonecutter keeps the branch for the version being built (see fabric/stonecutter.gradle.kts).
@file:Suppress("unused")

package dev.jukz.compat

//? if >=1.21.11 {
/*typealias Identifier = net.minecraft.resources.Identifier
typealias UIComponent = io.wispforest.owo.ui.core.UIComponent
typealias ParentUIComponent = io.wispforest.owo.ui.core.ParentUIComponent
typealias UIComponents = io.wispforest.owo.ui.component.UIComponents
typealias UIContainers = io.wispforest.owo.ui.container.UIContainers
typealias BaseUIComponent = io.wispforest.owo.ui.base.BaseUIComponent
typealias OwoUIGraphics = io.wispforest.owo.ui.core.OwoUIGraphics
typealias FocusSource = io.wispforest.owo.ui.core.UIComponent.FocusSource
*///?} else {
typealias Identifier = net.minecraft.resources.ResourceLocation
typealias UIComponent = io.wispforest.owo.ui.core.Component
typealias ParentUIComponent = io.wispforest.owo.ui.core.ParentComponent
typealias UIComponents = io.wispforest.owo.ui.component.Components
typealias UIContainers = io.wispforest.owo.ui.container.Containers
typealias BaseUIComponent = io.wispforest.owo.ui.base.BaseComponent
typealias OwoUIGraphics = io.wispforest.owo.ui.core.OwoUIDrawContext
typealias FocusSource = io.wispforest.owo.ui.core.Component.FocusSource
//?}

/** A jukz resource id, e.g. `id("textures/cosmetics/white.png")`. */
fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("jukz", path)
