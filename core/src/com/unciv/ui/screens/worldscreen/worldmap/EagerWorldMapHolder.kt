package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.UncivGame
import com.unciv.logic.map.TileMap
import com.unciv.ui.components.MapArrowType
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import java.lang.Float.max

/**
 * Today's only [WorldMapHolder] implementation: keeps one [WorldTileGroup] (and its 11
 * [com.unciv.ui.components.tilegroups.layers.TileLayer]s) alive per tile in [tileMap] at all
 * times, moved into a single [TileGroupMap] so all layers can be drawn depth-sorted across tiles.
 * This is the expensive-but-simple approach a pooled/recycling [WorldMapHolder] would replace.
 *
 * Extends [ZoomableScrollPane] directly, same as the original pre-[WorldMapHolder]-split design did
 * (rather than owning one as a child, an intermediate design this class went through before settling
 * here) - see [ZoomableScrollable][com.unciv.ui.components.widgets.ZoomableScrollable]'s doc for how
 * [getScrollX]/[setScrollX]/etc. being plain functions (not properties) is what makes that possible:
 * this class satisfies almost all of [WorldMapHolder]'s surface for free, just by inheriting
 * [ZoomableScrollPane]'s own concrete implementations, with explicit overrides only where this class
 * actually wants different behavior - [restrictX]/[restrictY] (explored-region-aware pan clamping),
 * [zoom] (also clamping city-button size via [onZoomed]), and [reloadMaxZoom] (map-width-aware zoom
 * limit on top of [ZoomableScrollPane]'s own settings-only default).
 *
 * A future pooled/recycling [WorldMapHolder] implementation need not extend [ZoomableScrollPane]
 * itself, or use one at all - see that interface's doc.
 */
class EagerWorldMapHolder(
    override val worldScreen: WorldScreen,
    override val tileMap: TileMap
) : ZoomableScrollPane(20f, 20f), AbstractWorldMapHolder {

    override val scrollFocusTarget: Actor get() = this

    // getStage() is a plain function on WorldMapHolder specifically so this already-inherited
    // Actor method (getStage()) satisfies it automatically, with no override needed here at all -
    // see WorldMapHolder's doc. getScrollX/setScrollX/getScrollY/setScrollY/getMaxX/getMaxY/
    // isPanning are likewise all already satisfied for free by ZoomableScrollPane's own inherited
    // ScrollPane methods - see ZoomableScrollable's doc.

    override var selectedTile: TileView? = null
    override val unitActionOverlays: ArrayList<Actor> = ArrayList()
    override val unitMovementPaths = HashMap<MapUnitView, ArrayList<TileView>>()
    override val unitConnectRoadPaths = HashMap<MapUnitView, List<TileView>>()
    override lateinit var currentTileSetStrings: TileSetStrings

    /** Every [WorldTileGroup] this holder has a live view for, keyed by tile - every tile in
     *  [tileMap], permanently (unlike a pooled holder). Private: [WorldMapHolder] only exposes
     *  single-tile lookup ([tileGroupOf]) and bulk iteration ([forEachVisibleTileGroup]), not a raw
     *  Map/Collection reference - see those methods' own docs for why. */
    private val tileGroupsByTileView = HashMap<TileView, WorldTileGroup>()

    override fun tileGroupOf(tileView: TileView): WorldTileGroup? = tileGroupsByTileView[tileView]

    override fun forEachVisibleTileGroup(op: (TileGroup) -> Unit) {
        for (group in tileGroupsByTileView.values) op(group)
    }

    /** Tiles [addArrow] has queued an arrow onto since the last [resetArrows] - lets [resetArrows]
     *  only touch tiles that actually might have arrows, instead of every live [tileGroupsByTileView]
     *  entry (up to ~8000 tiles here) on every single call, the overwhelming majority of which never
     *  have any arrows queued on a typical turn (a handful of moving/attacking units). Private: this
     *  is purely an implementation detail of this class's own [resetArrows]/[addArrow] - unlike
     *  [tileGroupOf]/[forEachVisibleTileGroup], nothing outside ever needs to know which tiles
     *  currently have a queued arrow. */
    private val tilesWithArrows = HashSet<TileView>()

    private lateinit var tileGroupMap: TileGroupMap<WorldTileGroup>

    init {
        if (Gdx.app.type == Application.ApplicationType.Desktop) setFlingTime(0f)
        continuousScrollingX = tileMap.mapParameters.worldWrap
        setupZoomPanListeners()
    }

    // restrictX/restrictY aren't part of any shared interface - they're ZoomableScrollPane's own
    // (unrelated) open methods, so Kotlin would otherwise silently keep using ZoomableScrollPane's
    // own plain (unclamped) versions instead of WorldMapHolder's explored-region-aware ones ("a class
    // member wins over an interface default") - hence the explicit override forwarding to it.
    override fun restrictX(deltaX: Float): Float = super<AbstractWorldMapHolder>.restrictX(deltaX)
    override fun restrictY(deltaY: Float): Float = super<AbstractWorldMapHolder>.restrictY(deltaY)

    override fun zoom(zoomScale: Float) {
        super.zoom(zoomScale)
        onZoomed()
    }

    override fun addTiles() {
        val tileSetStrings = TileSetStrings(worldScreen.gameInfo.ruleset, worldScreen.game.settings)
        currentTileSetStrings = tileSetStrings
        val tileMapView = worldScreen.selectedGameView.tileMapView
        val tileGroupsNew = tileMap.values.map { WorldTileGroup(tileMapView.getTile(it), tileSetStrings) }
        tileGroupMap = TileGroupMap(this, tileGroupsNew, continuousScrollingX)

        for (tileGroup in tileGroupsNew) tileGroupsByTileView[tileGroup.tileView] = tileGroup

        addClickListener(tileGroupMap)

        actor = tileGroupMap
        setSize(worldScreen.stage.width, worldScreen.stage.height)
        layout() // Fit the scroll pane to the contents - otherwise, setScroll won't work!
    }

    override fun addActorToTileGroupMap(actor: Actor) = tileGroupMap.addActor(actor)

    override fun setTileContentActHit(enabled: Boolean) {
        tileGroupMap.shouldAct = enabled
        tileGroupMap.shouldHit = enabled
    }

    /** [AbstractWorldMapHolder.resetArrows] has no shared default (see its own doc for why) - safe
     *  here because [tileGroupsByTileView] covers every tile in [tileMap] permanently, unlike a
     *  pooled holder. Uses [tilesWithArrows] rather than sweeping every entry - see that field's own
     *  doc for why that matters specifically here. */
    override fun resetArrows() {
        for (tileView in tilesWithArrows)
            tileGroupsByTileView[tileView]?.layerMisc?.resetArrows()
        tilesWithArrows.clear()
    }

    /** @see resetArrows */
    override fun addArrow(fromTileView: TileView, toTileView: TileView, arrowType: MapArrowType) {
        val group = tileGroupsByTileView[fromTileView] ?: return
        group.layerMisc.addArrow(toTileView.getTile(), arrowType)
        tilesWithArrows.add(fromTileView)
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
            minZoom = max(
                (width + pad) * mapZoomScale / getMaxX(),
                1f / maxWorldZoomOut
            )// add some extra padding offset

            // If the window becomes too wide and minZoom > maxZoom, we cannot zoom
            maxZoom = max(2f * minZoom, maxWorldZoomOut)
        }
        else
            super.reloadMaxZoom() // ZoomableScrollPane's own default zoom-limit-from-settings logic
    }
}
