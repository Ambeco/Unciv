package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.GUI
import com.unciv.logic.map.tile.Tile
import com.unciv.ui.components.recyclerview.widget.RecyclerView
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.view.CivView
import com.unciv.view.TileMapView

/**
 * Feeds a fixed-size pool of [WorldTileGroup]s to a [RecyclerView] over [allTiles] - one item per
 * tile, at that tile's index into [allTiles] - for [HexLayoutManager] to position and recycle.
 *
 * [ViewHolder.getItemViews] exposes each tile's [WorldTileGroup.layerWrapperGroups] - one stable
 * wrapper per [com.unciv.ui.components.tilegroups.layers.TileLayer] (terrain, features, borders,
 * ..., city button) - rather than the [WorldTileGroup] itself. This is what lets [getViewComparator]
 * stack every attached tile's *layers* across the whole visible pool in the same layer-major order
 * [com.unciv.ui.components.tilegroups.TileGroupMap]'s own shared per-layer containers give
 * [EagerWorldMapHolder]'s tiles "for free" (all tiles' terrain, then all tiles' features, ... then
 * all tiles' city buttons - so e.g. a city button on one tile can never be drawn over by terrain
 * from a neighboring tile, regardless of which tile the pool happens to have (re)bound last) -
 * something a single self-contained per-tile Actor fundamentally cannot express, since its own 11
 * layers are drawn as one atomic front-to-back unit before the next tile's are.
 *
 * [onBindViewHolder] normally does a full [TileGroup.rebind] (content and all) - a tile scrolling
 * into view needs its real content immediately, not just a positioned-but-blank slot waiting for
 * some other pass to notice it. The one exception is [GUI.isWorldLoaded] being false: that's only
 * true while [RecyclerWorldMapHolder.addTiles] (and the [WorldMapHolder.setCenterPosition] call
 * right after it) are running synchronously inside [WorldScreen]'s *own* constructor - before
 * [com.unciv.UncivGame.Current]'s `worldScreen` is set - and a full rebind there would reach
 * [com.unciv.ui.components.tilegroups.citybutton.CityButton.update]'s [GUI.getSelectedPlayer] call
 * and NPE right then (`worldScreen` still null). In that one case, [TileGroup.rebindPositionOnly]
 * is used instead - identity/position only, content deferred to the next
 * [WorldMapTileUpdater.updateTiles] pass, which the game already runs regularly once the screen is
 * live (matching [EagerWorldMapHolder]'s own freshly-constructed tiles, which are similarly
 * terrain-only - see its `init` block - until that same first pass).
 */
class HexTileAdapter(
    private val allTiles: List<Tile>,
    private val tileMapView: TileMapView,
    private val tileSetStrings: TileSetStrings,
    private val civView: () -> CivView,
    /** Called once a [ViewHolder] is (re)bound to [Tile] - see [onBindViewHolder]. Wired to
     *  [RecyclerWorldMapHolder]'s arrow overlay (see its own doc), which needs to know the instant a
     *  tile enters the pool, independent of whatever [WorldTileGroup] happens to represent it. */
    private val onTileBound: (Tile) -> Unit = {},
    /** Called once a [ViewHolder] previously bound to [Tile] is recycled - see [onViewRecycled].
     *  @see onTileBound */
    private val onTileUnbound: (Tile) -> Unit = {}
) : RecyclerView.Adapter<HexTileAdapter.ViewHolder>() {

    class ViewHolder(val tileGroup: WorldTileGroup) : RecyclerView.ViewHolder(), HexLayoutManager.ExtraPositionSync {
        override fun getItemViews(): List<Actor> = tileGroup.layerWrapperGroups
        // tileGroup itself isn't one of getItemViews() (its individual layer wrappers are instead -
        // see the class doc), but callers outside HexLayoutManager still read its x/y directly as
        // "this tile's current screen position" (WorldMapHolder.addOverlayOnTileGroup/animateMovement) -
        // see HexLayoutManager.ExtraPositionSync's doc for why this needs to stay in sync too.
        override fun syncExtraPosition(x: Float, y: Float) = tileGroup.setPosition(x, y)
    }

    /** Actor -> (item index within its holder's [ViewHolder.getItemViews], owning holder), for every
     *  layer-wrapper Actor any [ViewHolder] this adapter has ever created exposes - what
     *  [getViewComparator] needs to rank two arbitrary attached wrappers against each other (layer
     *  index primary, tile depth secondary) without a linear search through every live holder's own
     *  item list. Built once per [ViewHolder] (in [onCreateViewHolder]), not per bind: a holder's own
     *  11 wrapper Actor identities never change across rebinds to a different tile - only which [Tile]
     *  `tileGroup` currently points at does, and that's read fresh from the holder on every comparator
     *  call, never cached here. */
    private val wrapperInfo = HashMap<Actor, Pair<Int, ViewHolder>>()

    override fun getItemCount(): Int = allTiles.size

    override fun onCreateViewHolder(parent: RecyclerView, viewType: Int): ViewHolder {
        // A TileView is needed to construct a WorldTileGroup at all; onBindViewHolder immediately
        // rebinds it to the real tile for whatever position this holder is first used at, so which
        // tile is used here doesn't matter.
        val holder = ViewHolder(WorldTileGroup(tileMapView.getTile(allTiles[0]), tileSetStrings))
        for ((itemIndex, view) in holder.getItemViews().withIndex()) wrapperInfo[view] = itemIndex to holder
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tileView = tileMapView.getTile(allTiles[position])
        // Position (x, y) is set later by HexLayoutManager, not here. rebind()/rebindPositionOnly()
        // require *some* x/y though, so 0,0 is a harmless placeholder - the LayoutManager overwrites
        // it synchronously in the same layout pass, before anything renders.
        if (GUI.isWorldLoaded()) holder.tileGroup.rebind(tileView, 0f, 0f, civView())
        else holder.tileGroup.rebindPositionOnly(tileView, 0f, 0f)
        onTileBound(tileView.getTile())
    }

    /** [holder] is about to stop representing whatever [Tile] it currently does - either genuinely
     *  leaving the pool (see [RecyclerView.Recycler.recycleScrap]/[RecyclerView.Recycler.recycleViewAt])
     *  or about to be reused for a *different* position within the same layout pass (see
     *  [RecyclerView.Recycler.getHolderForPosition]'s same-pass reuse path) - either way, [onBindViewHolder]
     *  hasn't overwritten [ViewHolder.tileGroup] yet, so its current [WorldTileGroup.tile] is still
     *  the one actually being vacated. */
    override fun onViewRecycled(holder: ViewHolder) {
        onTileUnbound(holder.tileGroup.tile)
    }

    /**
     * Ranks every attached layer-wrapper Actor by (item index, tile depth) - matching
     * [com.unciv.ui.components.tilegroups.TileGroupMap]'s own per-layer container draw order, so e.g.
     * a city button on one tile always draws above terrain from a neighboring tile, regardless of
     * which tile the pool happens to have (re)bound to a "later" position (see
     * [RecyclerView.Adapter.getViewComparator]'s KDoc for why this needs to compare Actors directly,
     * not [ViewHolder]s, to express that). [RecyclerView.Recycler.beginAttachBatch]/
     * [RecyclerView.Recycler.endAttachBatch] (see [HexLayoutManager.onLayoutChildren]) use this to
     * insert every newly-attached wrapper into its correctly-interleaved slot the moment it's bound,
     * so an already-correctly-sorted attached set never needs re-touching just because membership
     * changed elsewhere - a previous version of this instead re-derived the *entire* draw order from
     * scratch on every membership change via a manual full resort in [HexLayoutManager] (`sortLayerMajor`,
     * now removed), which this makes unnecessary.
     */
    override fun getViewComparator(): Comparator<Actor> = Comparator { a, b ->
        val (itemIndexA, holderA) = wrapperInfo.getValue(a)
        val (itemIndexB, holderB) = wrapperInfo.getValue(b)
        if (itemIndexA != itemIndexB) return@Comparator itemIndexA - itemIndexB
        val posA = holderA.tileGroup.tile.position
        val posB = holderB.tileGroup.tile.position
        // Descending depth (larger x+y first) within a shared item index - matches the old
        // sortLayerMajor's sortedByDescending(x+y) processing order, where the *last*-processed
        // position within an index band ended up drawn on top.
        (posB.x + posB.y) - (posA.x + posA.y)
    }
}
