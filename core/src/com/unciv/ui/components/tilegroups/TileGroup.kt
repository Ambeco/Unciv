package com.unciv.ui.components.tilegroups

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Group
import com.unciv.view.CivView
import com.unciv.view.TileMapView
import com.unciv.view.TileView
import com.unciv.logic.map.tile.Tile
import com.unciv.ui.components.tilegroups.layers.*
import com.unciv.utils.DebugUtils
import kotlin.math.pow
import kotlin.math.sqrt

open class TileGroup(
    tileView: TileView,
    val tileSetStrings: TileSetStrings,
    groupSize: Float = TileGroupMap.groupSize + 4
) : Group() {

    /** A var because if we're spectator, the viewing civ can change as we select different civs to view as */
    var tileView: TileView = tileView
        private set

    val tile: Tile get() = tileView.getTile()
    /*
        Layers (reordered in TileGroupMap):
        1) Terrain
        2) Features: roads
        3) Borders
        4) Misc: improvements, resources, yields, citizens, arrows, starting locations (editor)
        5) Unit Arts
        6) Overlay:
        7) Unit Flags
        8) City Button
    */

    /** Cache simple but frequent calculations.
     * Honestly, I got these numbers empirically by printing `.x` and `.y` after `.center()`, and I'm not totally
     * clear on the stack of transformations that makes them work. But they are still exact ratios, AFAICT. */
    val hexagonImageWidth = groupSize * 1.5f
    val hexagonImageOriginX = hexagonImageWidth / 2f
    val hexagonImageOriginY = sqrt((hexagonImageWidth / 2f).pow(2) - (hexagonImageWidth / 4f).pow(2))
    val hexagonImagePosition = Pair(-hexagonImageOriginX / 3f, -hexagonImageOriginY / 4f)

    var isForceVisible = DebugUtils.VISIBLE_MAP
    var isForMapEditorIcon = false

    @Suppress("LeakingThis") val layerTerrain = TileLayerTerrain(this, groupSize)
    @Suppress("LeakingThis") val layerFeatures = TileLayerFeatures(this, groupSize)
    @Suppress("LeakingThis") val layerBorders = TileLayerBorders(this, groupSize)
    @Suppress("LeakingThis") val layerMisc = TileLayerMisc(this, groupSize)
    @Suppress("LeakingThis") val layerResource = TileLayerResource(this, groupSize)
    @Suppress("LeakingThis") val layerImprovement = TileLayerImprovement(this, groupSize)
    @Suppress("LeakingThis") val layerYield = TileLayerYield(this, groupSize)
    @Suppress("LeakingThis") val layerOverlay = TileLayerOverlay(this, groupSize)
    @Suppress("LeakingThis") val layerUnitArt = TileLayerUnitSprite(this, groupSize)
    @Suppress("LeakingThis") val layerUnitFlag = TileLayerUnitFlag(this, groupSize)
    @Suppress("LeakingThis") val layerCityButton = TileLayerCityButton(this, groupSize)

    private val allLayers = listOf(
        layerTerrain,
        layerFeatures, // includes roads
        layerBorders,
        layerResource,
        layerImprovement,
        layerMisc, // yields, citizens, arrows, starting locations (editor)
        layerYield,
        layerOverlay, // highlight, fog, crosshair
        layerUnitArt,
        layerUnitFlag,
        layerCityButton
    )

    /** Every layer's [TileLayer.standaloneWrapper], in the same (draw-bottom-to-top) order as
     *  [allLayers] - what [com.unciv.ui.screens.worldscreen.worldmap.HexTileAdapter.ViewHolder]
     *  exposes as this tile's individual RecyclerView items, instead of this [TileGroup] as a
     *  whole, so a pooled/recycling caller can stack them layer-major across tiles (matching
     *  [com.unciv.ui.components.tilegroups.TileGroupMap]'s own per-layer container draw order)
     *  rather than tile-major. */
    val layerWrapperGroups: List<Group> get() {
        for (layer in allLayers) ensureStandaloneWrapperAttached(layer)
        return allLayers.map { it.standaloneWrapper }
    }

    /**
     * Attaches [layer]'s [TileLayer.standaloneWrapper] as a child here - a no-op if it already is.
     * Inserted at whatever index keeps already-attached wrappers in [allLayers]' declared (draw-
     * bottom-to-top) order, rather than just appended: [TileLayer.addOwnedActor] can trigger this
     * for any layer independently and in any order (e.g. this class's own `init`, below, only ever
     * touches [layerTerrain]; [com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder.addArrow]
     * touches [layerMisc] alone, well outside a normal full [update] pass), so the *first* layer to
     * actually need a wrapper isn't necessarily the first in draw order - appending unconditionally
     * would silently corrupt it for exactly the callers ([EagerWorldMapHolder], Civilopedia, the map
     * editor) "standalone" mode exists to serve correctly.
     *
     * Deliberately per-layer rather than attaching all 11 the moment any *one* needs it: see
     * [TileLayer.standaloneWrapper]'s own doc for why that mattered for memory, not just correctness.
     */
    internal fun ensureStandaloneWrapperAttached(layer: TileLayer) {
        if (layer.isStandaloneWrapperAttached()) return
        val layerIndex = allLayers.indexOf(layer)
        val insertAt = allLayers.subList(0, layerIndex).count { it.isStandaloneWrapperAttached() }
        addActorAt(insertAt, layer.standaloneWrapper)
    }

    init {
        this.setSize(groupSize, groupSize)
        this.isTransform = false // Cannot be a NonTransformGroup as this causes font-rendered terrain to be upside-down
        layerTerrain.update(null)
    }

    /**
     * Repoints this [TileGroup] (and all 11 of its [TileLayer]s) at [newTileView] instead of the
     * tile it currently represents, moving it to grid position ([x], [y]) and refreshing every
     * layer for [viewingCiv] - all without reconstructing this [TileGroup] or any of its layers.
     * This is what makes a fixed-size pool of [TileGroup]s reusable as a scrollable viewport's
     * contents change, instead of needing one [TileGroup] per map tile permanently: see
     * [TileLayer.rebind]'s doc for why [TileLayer.update]'s ordinary incremental diffing isn't
     * safe to reuse for this by itself.
     */
    open fun rebind(newTileView: TileView, x: Float, y: Float, viewingCiv: CivView?) {
        rebindPositionOnly(newTileView, x, y)
        update(viewingCiv)
    }

    /**
     * The identity/position half of [rebind], without the trailing [update] call - for a caller
     * that needs this [TileGroup] repointed and repositioned immediately but can't safely call
     * [update] yet (e.g. [RecyclerWorldMapHolder]'s initial pool bind, which happens synchronously
     * during [WorldScreen]'s own constructor - before [com.unciv.GUI]/[com.unciv.UncivGame.Current]'s
     * `worldScreen` is set, which [com.unciv.ui.components.tilegroups.citybutton.CityButton.update]
     * needs via [com.unciv.GUI.getSelectedPlayer]). The caller must ensure a real [update] happens
     * before this tile is actually shown - a bare [rebindPositionOnly] leaves every layer showing
     * whatever content this pool slot last displayed, not [newTileView]'s. For
     * [RecyclerWorldMapHolder] specifically, the very next [WorldMapTileUpdater.updateTiles] pass
     * (already scheduled to run once the screen is actually live) does that.
     */
    fun rebindPositionOnly(newTileView: TileView, x: Float, y: Float) {
        tileView = newTileView
        setPosition(x, y)
        for (layer in allLayers) layer.rebind(x, y)
    }

    fun isViewable(viewingCiv: CivView) = isForceVisible
            || viewingCiv.canSeeTile(tileView)
            || viewingCiv.isSpectator()

    private fun reset() {
        layerTerrain.reset()
        layerBorders.reset()
        layerMisc.reset()
        layerResource.reset()
        layerImprovement.reset()
        layerYield.reset()
        layerOverlay.reset()
        layerUnitArt.reset()
        layerUnitFlag.reset()
    }

    private fun setAllLayersVisible(isVisible: Boolean) {
        for (layer in allLayers) layer.isVisible = isVisible
    }

    open fun update(viewingCiv: CivView? = null) {
        if (viewingCiv == null) {
            if (tileView.getCivView() != null)
                tileView = TileMapView(tile.tileMap, null).getTile(tile)
        } else {
            val newTileMapView = viewingCiv.gameView.tileMapView
            if (tileView.tileMapView !== newTileMapView)
                tileView = newTileMapView.getTile(tile)
        }
        layerMisc.removeHexOutline()
        layerMisc.hideTerrainOverlay()
        layerOverlay.hideHighlight()
        layerOverlay.hideCrosshair()
        layerOverlay.hideGoodCityLocationIndicator()

        // Do not update layers if tile is not explored by viewing player
        if (viewingCiv != null && !(isForceVisible || viewingCiv.hasExplored(tileView))) {
            if (tileView.getVisibleNeighbors().none()) {
                // No explored neighbors - hide all layers
                setAllLayersVisible(false)
            } else {
                // Has explored neighbors - reveal layers partially
                setAllLayersVisible(true) // visible, but...
                reset() // ...may not contain much
                layerOverlay.setUnexplored(viewingCiv)
            }
            return
        }

        setAllLayersVisible(true)

        for (layer in allLayers) layer.update(viewingCiv)
    }

    override fun draw(batch: Batch?, parentAlpha: Float) { super.draw(batch, parentAlpha) }
    override fun act(delta: Float) {}
}
