package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.UncivGame
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.ui.components.MapArrowType
import com.unciv.ui.components.ZoomGestureListener
import com.unciv.ui.components.recyclerview.widget.RecyclerView
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.tilegroups.layers.getArrowImage
import com.unciv.ui.components.tilegroups.layers.layoutArrowImage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.view.MapUnitView
import com.unciv.view.TileSingleAnimation
import com.unciv.view.TileView

/**
 * A [WorldMapHolder] implementation that extends [RecyclerView] directly - one item per tile (see
 * [HexTileAdapter]), positioned and recycled by [HexLayoutManager] - instead of wrapping a
 * [com.unciv.ui.components.widgets.ZoomableScrollPane] the way [EagerWorldMapHolder] (and this
 * class's own predecessor, before this rewrite) do. See [WorldMapHolder]'s doc for why an interface,
 * not an abstract class, is what makes this possible: nothing here needs `ScrollPane` at all.
 *
 * Because [RecyclerView] is a generic, Unciv-agnostic widget (see its own class doc - it's meant to
 * be liftable out of this project wholesale), everything Unciv/hex-map-specific lives here and in
 * [HexLayoutManager]/[HexTileAdapter] instead: **zoom** (via plain [Actor.setScale]/[Actor.setSize],
 * mirroring [com.unciv.ui.components.widgets.ZoomableScrollPane.zoom] - see [zoom]'s doc for why the
 * same size-adjustment trick is needed here too) and mouse-wheel/pinch-to-zoom input (via a
 * [ZoomGestureListener] - the same one [com.unciv.ui.components.widgets.ZoomableScrollPane] uses,
 * reused as-is since it was already Unciv/ScrollPane-agnostic).
 *
 * Unlike [ZoomableScrollPane], this holder needs no wrapping padded content [Group] to let the
 * camera center on an edge tile (see that class's `updatePadding`) - [getScrollX]/[getScrollY] are
 * defined directly as the viewport's own world-space *center* (see their KDoc), so the same effect (the
 * camera can scroll [width]/2 past either edge) falls out for free, with [HexLayoutManager]'s
 * [HexLayoutManager.scrollOffsetX]/[HexLayoutManager.scrollOffsetY] entirely unclamped by anything
 * but [restrictX]/[restrictY] themselves.
 *
 * KNOWN LIMITATIONS (vs [EagerWorldMapHolder]):
 * - The per-frame "disable act/hit while panning/zooming" optimization [setTileContentActHit]
 *   documents (~2x framerate while panning) is only partially ported: [hit] short-circuits to avoid
 *   descending into every tile's own `hit()`, but [Actor.act] on every visible tile still runs every
 *   frame regardless - see [setTileContentActHit]'s doc for why the full optimization doesn't port
 *   over cleanly onto a generic [RecyclerView] (which owns its own per-frame [RecyclerView.act] logic
 *   children rely on - fling, overscroll - unlike [com.badlogic.gdx.scenes.scene2d.ui.ScrollPane]).
 */
class RecyclerWorldMapHolder(
    override val worldScreen: WorldScreen,
    override val tileMap: TileMap
) : RecyclerView(), AbstractWorldMapHolder {

    override val scrollFocusTarget: Actor get() = this

    override var selectedTile: TileView? = null
    override val unitActionOverlays: ArrayList<Actor> = ArrayList()
    override val unitMovementPaths = HashMap<MapUnitView, ArrayList<TileView>>()
    override val unitConnectRoadPaths = HashMap<MapUnitView, List<TileView>>()
    override lateinit var currentTileSetStrings: TileSetStrings

    override var minZoom: Float = 0.5f
    override var maxZoom: Float = 1 / minZoom
    override val mapZoomScale: Float get() = scaleX
    override var mapPanningSpeed: Float = 6f

    override var onViewportChangedListener: ((width: Float, height: Float, viewport: Rectangle) -> Unit)? = null
    override var onPanStartListener: (() -> Unit)? = null
    override var onPanStopListener: (() -> Unit)? = null
    override var onZoomStartListener: (() -> Unit)? = null
    override var onZoomStopListener: (() -> Unit)? = null

    /** [HexLayoutManager] is the only [layoutManager] this holder ever installs - kept as a typed
     *  field alongside the generic (nullable) [layoutManager] property so [getScrollX]/[getScrollY]/
     *  [getMaxX]/[getMaxY]/[continuousScrollingX]/[reloadMaxZoom] can read/write its hex-specific
     *  surface ([HexLayoutManager.mapWidth]/[HexLayoutManager.mapHeight]/[HexLayoutManager.centerOn]/
     *  etc.) without a cast at every call site. Assigned in [addTiles], same as
     *  [EagerWorldMapHolder]'s own `scrollPane` and this class's pre-rewrite `tileGroupMap` were. */
    private lateinit var hexLayoutManager: HexLayoutManager

    override fun getMaxX(): Float = hexLayoutManager.mapWidth
    override fun getMaxY(): Float = hexLayoutManager.mapHeight

    /** The viewport's world-space horizontal *center* (see [HexLayoutManager]'s coordinate space) -
     *  matches [WorldMapHolder.setCenterPosition]'s convention (`tileGroup.x + tileGroup.width / 2`)
     *  directly, unlike [com.unciv.ui.components.widgets.ZoomableScrollPane.getScrollX] which needs a
     *  padding-offset correction to mean the same thing - see the class doc. Declared as a plain
     *  get/set function pair, not a `scrollX` property, matching [ZoomableScrollable]'s own contract -
     *  see that interface's doc for why. */
    override fun getScrollX(): Float = hexLayoutManager.scrollOffsetX + width / 2f
    override fun setScrollX(pixels: Float) {
        var target = pixels
        if (continuousScrollingX) {
            if (target < 0f) target += getMaxX()
            else if (target > getMaxX()) target -= getMaxX()
        }
        hexLayoutManager.centerOn(target, hexLayoutManager.scrollOffsetY + height / 2f)
        invalidate()
    }

    /** Y is inverted relative to [getScrollX] - `scrollY` grows downward on screen, world-space Y
     *  grows upward - matching [WorldMapHolder.setCenterPosition]'s `maxY - (tileGroup.y + ...)`
     *  convention (same inversion [com.unciv.ui.components.widgets.ZoomableScrollPane.getScrollY] uses). */
    override fun getScrollY(): Float = getMaxY() - (hexLayoutManager.scrollOffsetY + height / 2f)
    override fun setScrollY(pixels: Float) {
        // Inverse of getScrollY(): centerOn's second argument is the *world-space Y center* the
        // viewport should end up at (centerOn itself subtracts height/2 to get scrollOffsetY - see
        // its KDoc) - so solving getScrollY()'s own formula for that world-Y-center, given a target
        // getScrollY() value of `pixels`, is just `getMaxY() - pixels`. A previous version of this
        // also subtracted height/2 here, double-counting the same subtraction centerOn already does
        // - every call landed height/2 off from the intended target (setCenterPosition/scrollTo,
        // e.g. from a minimap click, always converged on the same wrong spot near an edge instead of
        // the actual clicked tile).
        hexLayoutManager.centerOn(hexLayoutManager.scrollOffsetX + width / 2f, getMaxY() - pixels)
        invalidate()
    }

    override var continuousScrollingX: Boolean
        get() = hexLayoutManager.continuousScrollingX
        set(value) { hexLayoutManager.continuousScrollingX = value }

    /** Set true between the first [touchDragged]-equivalent movement of a drag and its release - see
     *  the pan-tracking listener added in [addTiles]. Unlike [RecyclerView]'s own drag handling (which
     *  this doesn't touch), this exists purely to satisfy [WorldMapHolder.isPanning]/drive
     *  [onPanStartListener]/[onPanStopListener] for [AbstractWorldMapHolder.setupZoomPanListeners]. */
    private var panning = false
    override fun isPanning(): Boolean = panning

    private val zoomListener = ZoomListener()

    override fun addTiles() {
        val tileSetStrings = TileSetStrings(worldScreen.gameInfo.ruleset, worldScreen.game.settings)
        currentTileSetStrings = tileSetStrings
        val tileMapView = worldScreen.selectedGameView.tileMapView

        setAdapter(HexTileAdapter(
            tileMap.tileList, tileMapView, tileSetStrings,
            civView = { worldScreen.selectedGameView.civView },
            onTileBound = ::onTileBound,
            onTileUnbound = ::onTileUnbound
        ))

        hexLayoutManager = HexLayoutManager(
            tileMap,
            restrictX = { deltaX -> restrictX(deltaX) },
            restrictY = { deltaY -> restrictY(deltaY) }
        )
        hexLayoutManager.continuousScrollingX = tileMap.mapParameters.worldWrap
        layoutManager = hexLayoutManager

        addClickListener(this)
        addListener(panTrackingListener)
        addListener(zoomListener)

        setSize(worldScreen.stage.width, worldScreen.stage.height)
        layout() // Fit to the current stage size immediately - otherwise, setScroll won't work!
    }

    override fun addActorToTileGroupMap(actor: Actor) = addActor(actor)

    /** Overrides [AbstractWorldMapHolder.tileGroupOf]'s plain type-check default: a hit target here is never
     *  a [WorldTileGroup] directly (see [HexTileAdapter]'s doc - its item views are a tile's
     *  individual per-layer wrapper Actors instead), so it's resolved via [getChildViewHolder]'s
     *  own bookkeeping (every layer wrapper Actor is registered there, back to its owning holder)
     *  instead. */
    override fun tileGroupOf(child: Actor): WorldTileGroup? =
        (getChildViewHolder(child) as? HexTileAdapter.ViewHolder)?.tileGroup

    /** [RecyclerView] doesn't have a "pool" separate from its normal recycling machinery (unlike this
     *  class's pre-rewrite predecessor) - a [tileView]'s current [WorldTileGroup], if any, is
     *  resolved directly via [findViewHolderForAdapterPosition] at its tile's own adapter position,
     *  rather than looked up in some separately-maintained map - simpler than keeping a second map in
     *  sync with bind/rebind events, and just as cheap: every caller here (tile clicks) is triggered
     *  by a discrete user/game event, never a per-frame hot path. Arrows deliberately do *not* go
     *  through this - see [addArrow]'s own doc for why a pooled [WorldTileGroup] is the wrong place
     *  to hang one off. */
    override fun tileGroupOf(tileView: TileView): WorldTileGroup? =
        (findViewHolderForAdapterPosition(tileView.getTile().zeroBasedIndex) as? HexTileAdapter.ViewHolder)?.tileGroup

    /** @see tileGroupOf */
    override fun forEachVisibleTileGroup(op: (TileGroup) -> Unit) {
        for (position in recycler.getPositions()) {
            val holder = findViewHolderForAdapterPosition(position) as? HexTileAdapter.ViewHolder ?: continue
            op(holder.tileGroup)
        }
    }

    // ── arrows ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Replaces [WorldMapHolder]'s shared [addArrow]/[resetArrows] default, which draws an arrow as a
     * child Actor of its *source* tile's [WorldTileGroup] - fine for [EagerWorldMapHolder], where
     * every tile has one, permanently. Here, that [WorldTileGroup] can be recycled to a completely
     * different tile mid-scroll, silently dropping any arrow Actor hanging off it, with nothing to
     * ever redraw it short of the next unrelated `shouldUpdate`-triggered pass - a real bug this
     * replaces (arrows visibly disappearing while merely panning). The shared default was also
     * already broken the other way: it no-ops when the source tile isn't currently *in* the pool at
     * all (`tileGroupOf(fromTileView) == null`), so an off-screen unit's arrow into the current
     * viewport never appeared even right after a full update.
     *
     * [ArrowLifecycle] tracks *which* arrows should be live independent of any
     * [HexTileAdapter.ViewHolder]; this class only supplies what a live arrow actually *means* here -
     * an [Image] Actor, positioned in plain absolute/on-screen coordinates via
     * [HexLayoutManager.screenPositionOf] (see [layoutArrow]), unaffected by which physical tiles
     * happen to be pooled right now. Never reparented into either endpoint's own layer wrapper - it
     * stays a standalone child of this [RecyclerView] throughout (like [unitActionOverlays]) - so an
     * arrow's endpoint tiles scrolling in and out never touches its actual
     * position/rotation/parenting, only whether it exists at all.
     */
    private val arrowActors = HashMap<ArrowLifecycle.ArrowSpec, Image>()

    private val arrowLifecycle = ArrowLifecycle(
        onActivated = { spec -> layoutArrow(spec, getArrowImage(spec.type, currentTileSetStrings).also {
            arrowActors[spec] = it
            addActor(it)
        }) },
        onDeactivated = { spec -> arrowActors.remove(spec)?.remove() }
    )

    override fun resetArrows() = arrowLifecycle.reset()

    override fun addArrow(fromTileView: TileView, toTileView: TileView, arrowType: MapArrowType) =
        arrowLifecycle.add(fromTileView.getTile(), toTileView.getTile(), arrowType) { tile -> isTileBound(tile) }

    private fun isTileBound(tile: Tile) = tile.zeroBasedIndex in recycler.getPositions()

    private fun layoutArrow(spec: ArrowLifecycle.ArrowSpec, actor: Image) {
        val fromPos = hexLayoutManager.screenPositionOf(spec.from)
        layoutArrowImage(actor, spec.from, spec.to, fromPos.x, fromPos.y)
    }

    /** [HexTileAdapter]'s bind callback (see [addTiles]): [tile] just became bound to *some*
     *  [HexTileAdapter.ViewHolder]. */
    private fun onTileBound(tile: Tile) = arrowLifecycle.onTileBound(tile)

    /** [HexTileAdapter]'s recycle callback (see [addTiles]): [tile] just stopped being bound to
     *  whatever [HexTileAdapter.ViewHolder] it had. */
    private fun onTileUnbound(tile: Tile) = arrowLifecycle.onTileUnbound(tile)

    /** Repositions every live arrow Actor - called every [layout] pass alongside [HexLayoutManager]'s
     *  own tile reposition loop, since an arrow's on-screen position depends on the current scroll
     *  offset exactly the way a tile's does (see [layoutArrow]). */
    private fun layoutArrows() {
        for ((spec, actor) in arrowActors) layoutArrow(spec, actor)
    }

    /**
     * See [AbstractWorldMapHolder.setupZoomPanListeners]'s doc for the framerate motivation. [RecyclerView]
     * doesn't expose a single flag anything downstream of [Actor.act] can gate the way
     * [com.unciv.ui.components.tilegroups.TileGroupMap.shouldAct]/`shouldHit` did for
     * [EagerWorldMapHolder] - [RecyclerView.act] and [RecyclerView.layout] both need to run every
     * frame regardless (fling/overscroll physics, and the LayoutManager re-binding tiles as they
     * scroll into view, neither of which panning/zooming should pause). Only the *hit-testing* half
     * of the optimization is ported, via [hit] - see there.
     */
    private var actHitEnabled = true
    override fun setTileContentActHit(enabled: Boolean) {
        actHitEnabled = enabled
    }

    /** While [actHitEnabled] is false (actively panning/zooming - see [setTileContentActHit]),
     *  short-circuits before descending into every visible tile's own [Actor.hit] - same idea as
     *  [com.badlogic.gdx.scenes.scene2d.Group.hit]'s own early-outs, just gated by our flag instead
     *  of visibility/touchability. Returns this [RecyclerView] itself as the hit target (matching
     *  what "no interactive descendant was hit" would otherwise report up the chain to) rather than
     *  `null`, so a drag in progress doesn't lose its hit target mid-gesture. */
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? {
        if (actHitEnabled) return super.hit(x, y, touchable)
        if (touchable && this.touchable != com.badlogic.gdx.scenes.scene2d.Touchable.enabled) return null
        return if (x >= 0f && x < width && y >= 0f && y < height) this else null
    }

    override fun reloadMaxZoom() {
        val maxWorldZoomOut = UncivGame.Current.settings.maxWorldZoomOut
        val mapRadius = tileMap.mapParameters.mapSize.radius

        // Limit max zoom out by the map width
        val enableZoomLimit = (mapRadius < 21 && maxWorldZoomOut < 3f) || (mapRadius > 20 && maxWorldZoomOut < 4f)

        if (enableZoomLimit) {
            // For world-wrap we limit minimal possible zoom to content width + some extra offset
            // to hide one column of tiles so that the player doesn't see it teleporting from side to side
            val pad = if (continuousScrollingX) width / mapRadius * 0.7f else 0f
            minZoom = java.lang.Float.max(
                (width + pad) * mapZoomScale / getMaxX(),
                1f / maxWorldZoomOut
            )// add some extra padding offset

            // If the window becomes too wide and minZoom > maxZoom, we cannot zoom
            maxZoom = java.lang.Float.max(2f * minZoom, maxWorldZoomOut)
        } else {
            // Same default (settings-only) zoom-limit logic
            // com.unciv.ui.components.widgets.ZoomableScrollPane.reloadMaxZoom uses - no ScrollPane
            // here to delegate to, so inlined directly.
            maxZoom = maxWorldZoomOut
            minZoom = 1f / maxZoom
            if (scaleX < minZoom) zoom(1f)
        }
    }

    // ── zoom (Unciv-specific - see the class doc for why this isn't in RecyclerView itself) ──────

    override fun isZooming(): Boolean = zoomListener.isZooming

    /**
     * Mirrors [com.unciv.ui.components.widgets.ZoomableScrollPane.zoom] exactly, including its
     * width/height adjustment: scaling this [Actor] via [Actor.setScale] alone would also change how
     * large it *appears* to its parent without changing its own reported [Actor.getWidth]/
     * [Actor.getHeight] - since those are what [getMaxX]/[getMaxY]-relative math ([restrictX], [getViewport],
     * [HexLayoutManager]'s own viewport-radius calc) and the rest of the scene (e.g. [WorldScreen]
     * sizing this to the full stage) all read, they'd silently stop matching the actual on-screen
     * footprint post-zoom without this correction.
     */
    override fun zoom(zoomScale: Float) {
        val newZoom = zoomScale.coerceIn(minZoom, maxZoom)
        val oldZoom = scaleX
        if (newZoom == oldZoom) return

        val newWidth = width * oldZoom / newZoom
        val newHeight = height * oldZoom / newZoom
        setScale(newZoom)
        setSize(newWidth, newHeight)

        onViewportChanged()
        onZoomed()
    }

    override fun zoomIn(immediate: Boolean) {
        if (immediate) zoom(scaleX / 0.8f) else zoomListener.zoomIn(0.8f)
    }

    override fun zoomOut(immediate: Boolean) {
        if (immediate) zoom(scaleX * 0.8f) else zoomListener.zoomOut(0.8f)
    }

    /** Mouse-wheel/pinch-to-zoom input - the same [ZoomGestureListener] subclass shape
     *  [com.unciv.ui.components.widgets.ZoomableScrollPane.ZoomListener] uses, adapted to call this
     *  class's own [zoom]/[scrollTo] instead of a wrapped `ScrollPane`'s. */
    private inner class ZoomListener : ZoomGestureListener({ Vector2(stage!!.width, stage!!.height) }) {

        inner class ZoomAction : TemporalAction() {
            var startingZoom: Float = 1f
            var finishingZoom: Float = 1f
            var currentZoom: Float = 1f

            init {
                duration = 0.3f
                interpolation = Interpolation.fastSlow
            }

            override fun begin() { isZooming = true }
            override fun end() { zoomAction = null; isZooming = false }
            override fun update(percent: Float) {
                currentZoom = MathUtils.lerp(startingZoom, finishingZoom, percent)
                zoom(currentZoom)
            }
        }

        private var zoomAction: ZoomAction? = null
        var isZooming = false

        fun zoomOut(zoomMultiplier: Float = 0.82f) {
            val currentAction = zoomAction
            if (scaleX <= minZoom) {
                currentAction?.finish()
                return
            }
            if (currentAction != null) {
                currentAction.startingZoom = currentAction.currentZoom
                currentAction.finishingZoom *= zoomMultiplier
                currentAction.restart()
            } else {
                val newAction = ZoomAction()
                newAction.startingZoom = scaleX
                newAction.finishingZoom = scaleX * zoomMultiplier
                zoomAction = newAction
                addAction(newAction)
            }
        }

        fun zoomIn(zoomMultiplier: Float = 0.82f) {
            val currentAction = zoomAction
            if (scaleX >= maxZoom) {
                currentAction?.finish()
                return
            }
            if (currentAction != null) {
                currentAction.startingZoom = currentAction.currentZoom
                currentAction.finishingZoom /= zoomMultiplier
                currentAction.restart()
            } else {
                val newAction = ZoomAction()
                newAction.startingZoom = scaleX
                newAction.finishingZoom = scaleX / zoomMultiplier
                zoomAction = newAction
                addAction(newAction)
            }
        }

        override fun pinch(translation: Vector2, scaleChange: Float) {
            if (!isZooming) {
                isZooming = true
                onZoomStartListener?.invoke()
            }
            scrollTo(
                getScrollX() - translation.x / scaleX,
                getScrollY() + translation.y / scaleY,
                true
            )
            zoom(scaleX * scaleChange)
        }

        override fun pinchStop() {
            isZooming = false
            onZoomStopListener?.invoke()
        }

        override fun scrolled(amountX: Float, amountY: Float): Boolean {
            if (amountX > 0 || amountY > 0) zoomOut() else zoomIn()
            return true
        }
    }

    // ── panning ─────────────────────────────────────────────────────────────────────────────────

    /** Purely observational (always returns `true`/does nothing to consume the event) - tracks
     *  [panning] and fires [onPanStartListener]/[onPanStopListener] alongside [RecyclerView]'s own
     *  (separate, unaffected) drag handling - see [panning]'s doc for why this needs its own listener
     *  rather than reading [RecyclerView]'s private drag state. */
    private val panTrackingListener = object : InputListener() {
        override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) = true

        override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
            if (!panning) {
                panning = true
                onPanStartListener?.invoke()
            }
        }

        override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
            if (panning) {
                panning = false
                onPanStopListener?.invoke()
            }
        }
    }

    // ── scrolling ───────────────────────────────────────────────────────────────────────────────

    override fun scrollTo(x: Float, y: Float, immediately: Boolean): Boolean {
        if (getScrollX() == x && getScrollY() == y) return false
        // API CHANGE vs com.unciv.ui.components.widgets.ZoomableScrollPane.scrollTo: no separate
        // non-immediate animated path (that used a FloatAction lerping scrollX/scrollY over 0.4s) -
        // RecyclerView.smoothScrollToPosition's own anchor-walking animation isn't a drop-in
        // replacement (it steps between adapter positions, not two arbitrary world points), and nothing
        // yet calls scrollTo(immediately = false) for this implementation - WorldScreen's own callers
        // (setCenterPosition, the load-game scroll restore) all pass true. Deferred until a caller
        // actually needs it, same "don't build it speculatively" reasoning this file's KNOWN
        // LIMITATIONS already use elsewhere.
        setScrollX(x)
        setScrollY(y)
        updateVisualScroll()
        return true
    }

    override fun updateVisualScroll() {
        invalidate()
        layout()
    }

    /**
     * Overrides [WorldMapHolder.setCenterPosition]'s shared default entirely, rather than just
     * forcing the target tile into the pool first and delegating the rest - that default computes
     * its scroll target from an already-bound tile's own `tileGroup.x`/`.y`, which works for
     * [EagerWorldMapHolder] (there, a bound tile's position is a fixed *absolute world* coordinate,
     * unaffected by scrolling) but is actively wrong here: [HexLayoutManager] repositions every
     * bound [WorldTileGroup] to a *screen-relative* coordinate every layout pass (`tilePosition -
     * scrollOffset`), which is close to `(width/2, height/2)` for whatever tile was *just* centered
     * on - a constant, click-independent value. Calling the shared default after centering here
     * would immediately re-scroll to that bogus, always-the-same target - this was a real bug
     * (clicking anywhere on the minimap always jumped to the same spot). Since this holder knows
     * every tile's exact world position up front (via [HexLayoutManager]), it scrolls straight there
     * directly instead, forcing an immediate [layout] pass so the target tile is actually live (i.e.
     * [tileGroupOf] resolves it) - then replicates the shared default's remaining behavior (unit
     * selection, the highlight blink) itself, using the already-correct scroll position instead of
     * recomputing (and overwriting) it.
     */
    override fun setCenterPosition(vector: HexCoord, immediately: Boolean, selectUnit: Boolean, forceSelectUnit: MapUnit?): Boolean {
        val tile = tileMap.getOrNull(vector.x, vector.y) ?: return false
        scrollToPosition(tile.zeroBasedIndex)
        layout()

        val tileGroup = tileGroupOf(worldScreen.selectedGameView.tileMapView.getTile(tile)) ?: return false
        selectedTile = tileGroup.tileView
        if (selectUnit || forceSelectUnit != null)
            worldScreen.bottomUnitTable.tileSelected(selectedTile!!, forceSelectUnit?.let { worldScreen.selectedGameView.getForeignMapUnitView(it).tryGetMapUnitView() })

        tileGroup.tileView.playAnimation(TileSingleAnimation.SELECTION_BLINK) // "look here" flash

        worldScreen.shouldUpdate = true
        return true
    }

    override fun getViewport(rect: Rectangle) {
        rect.x = hexLayoutManager.scrollOffsetX
        rect.y = hexLayoutManager.scrollOffsetY
        rect.width = width
        rect.height = height
    }

    private fun getViewport() = Rectangle().also { getViewport(it) }

    override fun onViewportChanged() {
        onViewportChangedListener?.invoke(getMaxX(), getMaxY(), getViewport())
    }

    override fun doKeyOrMousePanning(deltaX: Float, deltaY: Float) {
        if (deltaX == 0f && deltaY == 0f) return
        val amountToMove = mapPanningSpeed / scaleX
        setScrollX(restrictX(deltaX * amountToMove))
        setScrollY(restrictY(deltaY * amountToMove))
        updateVisualScroll()
    }

    override fun layout() {
        super.layout()
        layoutArrows()
        onViewportChanged()
    }
}
