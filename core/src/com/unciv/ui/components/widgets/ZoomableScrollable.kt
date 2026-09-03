package com.unciv.ui.components.widgets

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Action

/**
 * The public API of [ZoomableScrollPane] that callers pan/zoom/scroll through - implemented
 * directly by [ZoomableScrollPane] itself, and forwarded onto whichever [ZoomableScrollPane]
 * instance backs a given [com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder] implementation
 * (see that interface's doc for why the pane isn't owned at the shared interface level).
 *
 * Replaces the former separate `KeyPannable`/`Zoomable` interfaces with one: callers needing only
 * part of this (e.g. [com.unciv.ui.screens.worldscreen.ZoomButtonPair] only needs [zoomIn]/[zoomOut],
 * [com.unciv.ui.components.input.KeyboardPanningListener] only needs [doKeyOrMousePanning] plus
 * [addAction]/[removeAction]) can just take a [ZoomableScrollable] and use only what they need,
 * rather than needing a second interface.
 *
 * [addAction]/[removeAction] come for free from [com.badlogic.gdx.scenes.scene2d.Actor] on every
 * real implementor (both [ZoomableScrollPane] and every `WorldMapHolder` implementation are Actors) -
 * declared here only so callers can reach them through this interface without needing the concrete
 * Actor type.
 *
 * [getScrollX]/[setScrollX]/[getScrollY]/[setScrollY]/[getMaxX]/[getMaxY]/[isPanning] are declared as
 * plain get/set-named functions, not `scrollX`/`scrollY`/`maxX`/`maxY`/`isPanning` properties, even
 * though [ZoomableScrollPane]'s own public API *is* exactly those properties: [ZoomableScrollPane]
 * inherits concrete Java implementations of those exact names from Gdx's own `ScrollPane`, and a
 * Kotlin property *override* of the same name over an already-concrete inherited Java method is a
 * "platform declaration clash" - the generated `getScrollX()`/`setScrollX(float)` accessor bytecode
 * collides with the identical methods already inherited from `ScrollPane`. A plain function
 * declaration doesn't have this problem (same reasoning as [addAction]/[isZooming] here, or
 * `WorldMapHolder.getStage()`): Kotlin lets *any* method - inherited from Java or not - satisfy an
 * abstract function of matching signature, so [ZoomableScrollPane] (and therefore every
 * `WorldMapHolder` implementation extending it, e.g. `EagerWorldMapHolder`) satisfies these five for
 * free, with zero overrides, purely by inheriting `ScrollPane`'s own `getScrollX()`/`setScrollX(float)`/
 * etc. An implementation with no backing `ScrollPane` at all (e.g. `RecyclerWorldMapHolder`) simply
 * implements these as ordinary functions instead of a property - the only real cost of avoiding the
 * clash is call sites write `getScrollX()`/`setScrollX(x)` instead of `.scrollX`/`.scrollX = x`.
 */
interface ZoomableScrollable {
    var minZoom: Float
    var maxZoom: Float
    val mapZoomScale: Float
    var isAutoScrollEnabled: Boolean
    var mapPanningSpeed: Float
    var continuousScrollingX: Boolean
    var onViewportChangedListener: ((width: Float, height: Float, viewport: Rectangle) -> Unit)?
    var onPanStartListener: (() -> Unit)?
    var onPanStopListener: (() -> Unit)?
    var onZoomStartListener: (() -> Unit)?
    var onZoomStopListener: (() -> Unit)?

    fun getScrollX(): Float
    fun setScrollX(pixels: Float)
    fun getScrollY(): Float
    fun setScrollY(pixels: Float)
    fun getMaxX(): Float
    fun getMaxY(): Float
    fun isPanning(): Boolean

    fun isZooming(): Boolean
    fun zoom(zoomScale: Float)
    fun zoomIn(immediate: Boolean = false)
    fun zoomOut(immediate: Boolean = false)
    fun scrollTo(x: Float, y: Float, immediately: Boolean = false): Boolean
    fun updateVisualScroll()
    fun doKeyOrMousePanning(deltaX: Float, deltaY: Float)
    fun getViewport(rect: Rectangle)
    fun onViewportChanged()
    fun layout()

    fun addAction(action: Action?)
    fun removeAction(action: Action?)
}
