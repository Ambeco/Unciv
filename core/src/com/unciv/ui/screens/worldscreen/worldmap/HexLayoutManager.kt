package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.ui.components.recyclerview.widget.RecyclerView
import com.unciv.ui.components.tilegroups.TileGroupMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Positions and recycles a [RecyclerView]'s items over a hex-grid map - see [RecyclerWorldMapHolder].
 * Unlike [com.unciv.ui.components.recyclerview.widget.LinearLayoutManager]/[com.unciv.ui.components.recyclerview.widget.GridLayoutManager],
 * every item has a fixed, precomputed pixel position (from its tile's hex coordinate - the same
 * formula [TileGroupMap] lays tiles out with) rather than a variable measured size walked out from
 * an anchor, so this needs none of their anchor+offset bookkeeping: [scrollOffsetX]/[scrollOffsetY]
 * are plain absolute pixel offsets from the map's own normalized origin, and every visible item's
 * on-screen position is simply `tilePosition - (scrollOffsetX, scrollOffsetY)`, always exact.
 *
 * Visible items are picked the same way the old hand-rolled pool implementation this replaces
 * computed pool membership: a hex-distance-from-viewport-center radius, sized so the circle queried
 * always fully contains the viewport rectangle regardless of zoom/aspect ratio (see
 * [radiusToCoverViewport]) - but that circle is only used to enumerate *candidates* cheaply (a
 * hex-ring walk via [TileMap.forEachTileInDistance], not an O(map size) scan); each candidate is
 * then checked against the real on-screen rectangle before actually being bound (see
 * [onLayoutChildren]'s `needed` set) - for a wide-aspect-ratio viewport, the covering circle's own
 * area can be several times the rectangle's, so skipping this filter meant binding several times as
 * many [com.unciv.ui.components.tilegroups.WorldTileGroup]s/
 * [com.unciv.ui.components.tilegroups.layers.TileLayer]s as were ever actually visible. The
 * [RecyclerView.Adapter] using this ([HexTileAdapter]) must expose one item per [Tile] in
 * [TileMap.tileList], at that tile's [Tile.zeroBasedIndex].
 *
 * Every needed tile's position is recomputed fresh on *every* [onLayoutChildren] pass (not just once
 * at bind/rebind time) - including [continuousScrollingX]'s choice of which periodic copy of a tile
 * to show (see [closestPeriodicX]) - so unlike the old pool implementation, there's no lag between a
 * pool slot being rebound and it appearing in the visually-correct spot: every visible tile is always
 * exactly where it should be for the *current* scroll position, every frame.
 *
 * [restrictX]/[restrictY] let the owning holder hook into scrolling the same way
 * [com.unciv.ui.components.widgets.ZoomableScrollPane]'s identically-named overridable methods do
 * (e.g. [WorldMapHolder]'s explored-region pan clamp) - default to no restriction, so this class is
 * independently testable without a real [WorldMapHolder].
 */
class HexLayoutManager(
    private val tileMap: TileMap,
    /** See [WorldMapHolder.restrictX]'s KDoc for the exact contract a non-null value must follow -
     *  `null` (the default) means unrestricted: pass straight through with no clamp. Deliberately
     *  nullable rather than defaulting to `{ it }` (a stateless identity function): the real contract
     *  needs *this instance's own* current [scrollOffsetX] (which a bare `(Float) -> Float` default
     *  can't close over) to compute the unclamped result - see [scrollHorizontallyBy]. */
    var restrictX: ((Float) -> Float)? = null,
    /** @see restrictX */
    var restrictY: ((Float) -> Float)? = null
) : RecyclerView.LayoutManager() {

    /** World-space pixel position of [tile], *not* normalized to the map's own bounding box (see
     *  [worldPosition] for that) - the same formula [TileGroupMap]'s `init` lays tiles out with
     *  (`HexMath.hex2WorldCoords(tile.position) * 0.8f * groupSize`), computed independently here
     *  since this class deliberately doesn't depend on a live [TileGroupMap] instance at all - see
     *  [RecyclerWorldMapHolder]'s doc for why. Recomputed on every call rather than cached per tile:
     *  it's two multiplications, cheaper than the [java.util.HashMap] lookup a cache would need, and
     *  a per-tile cache was real, measurable memory for a large map's worth of [Tile]s that's never
     *  actually needed - [tileMap] itself is the only per-tile state this class keeps at all. */
    private fun rawWorldPosition(tile: Tile): Vector2 {
        val p = HexMath.hex2WorldCoords(tile.position)
        return Vector2(p.x * 0.8f * TileGroupMap.groupSize, p.y * 0.8f * TileGroupMap.groupSize)
    }

    /** [rawWorldPosition]'s bounding box over every tile on [tileMap] - just the 4 floats, not a
     *  retained per-tile map (see [rawWorldPosition]'s doc) - computed once, in [init], since (unlike
     *  a single tile's own position) this genuinely does need every tile visited to know. */
    private val minX: Float
    private val minY: Float
    private val maxRawX: Float
    private val maxRawY: Float

    init {
        var loX = 0f; var loY = 0f; var hiX = 0f; var hiY = 0f
        var first = true
        for (tile in tileMap.tileList) {
            val raw = rawWorldPosition(tile)
            if (first) {
                loX = raw.x; hiX = raw.x; loY = raw.y; hiY = raw.y
                first = false
            } else {
                if (raw.x < loX) loX = raw.x
                if (raw.x > hiX) hiX = raw.x
                if (raw.y < loY) loY = raw.y
                if (raw.y > hiY) hiY = raw.y
            }
        }
        minX = loX; minY = loY; maxRawX = hiX; maxRawY = hiY
    }

    /** Total world-space extent of the map (all tiles' positions, normalized so the lowest corner
     *  sits at (0,0)) plus one tile's worth of padding - hex tile images extend beyond their own
     *  cell (see [EDGE_MARGIN]'s doc) so the true visual extent is a bit larger than the raw position
     *  spread. These are [WorldMapHolder.maxX]/[maxY] - the pan-clamp bounds [restrictX]/[restrictY]
     *  are meant to read - and [continuousScrollingX]'s world-wrap seam width. */
    val mapWidth: Float get() = maxRawX - minX + TileGroupMap.groupSize
    val mapHeight: Float get() = maxRawY - minY + TileGroupMap.groupSize

    /** World-space pixel position of [tile], normalized so the map's own bounding box starts at
     *  (0,0) - i.e. in the same coordinate space [scrollOffsetX]/[scrollOffsetY] are. */
    private fun worldPosition(tile: Tile): Vector2 {
        val raw = rawWorldPosition(tile)
        return Vector2(raw.x - minX, raw.y - minY)
    }

    /** Inverse of [worldPosition]'s coordinate space, for finding which tile a given normalized
     *  pixel position falls on - mirrors [TileGroupMap.getPositionalVector]'s exact math (its
     *  `bottomX`/`bottomY` play the same role [minX]/[minY] do here). */
    private fun pixelToHexCoord(normalizedPixel: Vector2): Vector2 {
        val trueGroupSize = 0.8f * TileGroupMap.groupSize
        return Vector2(minX, minY).add(normalizedPixel)
            .sub(TileGroupMap.groupSize / 2f, TileGroupMap.groupSize / 2f)
            .scl(1f / trueGroupSize)
    }

    /**
     * Nearest real tile to [origin] - an arbitrary [HexCoord] not necessarily on the map at all
     * (specifically, the exact rounded hex coordinate under the viewport's geometric center, which
     * can land in one of a hexagonal map's "cut corners" - see [onLayoutChildren]'s doc). Searches
     * outward ring by ring (nearest first) via [HexMath.getHexCoordsInDistance], capped at
     * [NEAREST_TILE_SEARCH_RADIUS] rings - generous enough for any cut-corner gap on a real map
     * (those are only ever a few tiles deep at most), while staying cheap regardless of overall map
     * size. Returns `null` only if [origin] is implausibly far from *every* real tile (e.g. an
     * absurdly tiny or malformed map) - callers fall back further from there.
     */
    private fun findNearestTile(origin: HexCoord): Tile? {
        for (candidate in HexMath.getHexCoordsInDistance(origin, NEAREST_TILE_SEARCH_RADIUS, continuousScrollingX)) {
            tileMap.getOrNull(candidate.x, candidate.y)?.let { return it }
        }
        return null
    }

    /** Whether the map wraps horizontally - mirrors [com.unciv.ui.components.widgets.ZoomableScrollPane.continuousScrollingX]. */
    var continuousScrollingX: Boolean = false

    private var lastCenterTile: Tile? = null
    private var lastRadius = -1

    override fun canScrollHorizontally(): Boolean = true
    override fun canScrollVertically(): Boolean = true

    override fun scrollHorizontallyBy(dx: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        if (dx == 0f) return 0f
        val before = scrollOffsetX
        // restrictX takes "how much to subtract from the current value" and returns the clamped
        // absolute result (see WorldMapHolder.restrictX's KDoc) - passing -dx makes that arithmetic
        // (current - (-dx) = current + dx) match this class's own "dx is added directly" convention.
        // IMPORTANT: restrictX/WorldMapHolder.getScrollX() operate in *viewport-center* terms
        // (getScrollX() = scrollOffsetX + width/2 - see RecyclerWorldMapHolder.getScrollX's KDoc),
        // not this class's own *left-edge* scrollOffsetX - the callback's return value has to be
        // converted back (subtract width/2) before it means the same thing as scrollOffsetX.
        // Skipping that conversion was a real bug: every drag frame added a full extra width/2 on
        // top of the actual delta, since restrictX(-dx) returns (scrollOffsetX + width/2) + dx, not
        // scrollOffsetX + dx - compounding every single scroll call into an enormous jump.
        val halfWidth = (recyclerView?.width ?: 0f) / 2f
        var newScrollX = restrictX?.invoke(-dx)?.minus(halfWidth) ?: (before + dx)
        var consumed = newScrollX - before
        if (continuousScrollingX) {
            val width = mapWidth
            if (newScrollX < 0f) { newScrollX += width; consumed = dx }
            else if (newScrollX > width) { newScrollX -= width; consumed = dx }
        }
        scrollOffsetX = newScrollX
        onLayoutChildren(recycler, state)
        return consumed
    }

    override fun scrollVerticallyBy(dy: Float, recycler: RecyclerView.Recycler, state: RecyclerView.State): Float {
        if (dy == 0f) return 0f
        val before = scrollOffsetY
        // Same center-vs-edge conversion restrictX needs (see its comment above): recovering
        // scrollOffsetY from a restrictY result means solving getScrollY() = mapHeight -
        // (scrollOffsetY + height/2) for scrollOffsetY, not just subtracting half the viewport.
        //
        // The sign ALSO needs flipping here, despite WorldMapHolder.restrictY's own convention
        // (unlike restrictX) adding its argument directly (`getScrollY() + deltaY`) - because
        // getScrollY() moves the *opposite* way from scrollOffsetY (that same "mapHeight minus"
        // inversion), the two effects cancel out: solving "toOffset(restrictY(arg)) == before + dy"
        // for arg gives arg == -dy, the same relationship restrictX needs for the opposite reason
        // (its own getScrollX() is a direct, uninverted read of scrollOffsetX, but restrictX itself
        // subtracts its argument instead of adding). Verified by concrete substitution, not just
        // the (previously wrong, "no flip needed") reasoning by analogy to restrictX's own comment.
        val halfHeight = (recyclerView?.height ?: 0f) / 2f
        scrollOffsetY = restrictY?.invoke(-dy)?.let { mapHeight - halfHeight - it } ?: (before + dy)
        val consumed = scrollOffsetY - before
        onLayoutChildren(recycler, state)
        return consumed
    }

    override fun scrollToPosition(position: Int) {
        val tile = tileMap.tileList.getOrNull(position) ?: return
        val pos = worldPosition(tile)
        centerOn(pos.x, pos.y)
    }

    /** Directly (immediately, no animation) centers the viewport on the given normalized
     *  world-space point (see [worldPosition]'s coordinate space) - [scrollOffsetX]/[scrollOffsetY]
     *  have a `protected` setter (only a [RecyclerView.LayoutManager] may assign them), so this is
     *  the accessor [RecyclerWorldMapHolder] uses for both [scrollToPosition] and its own
     *  [com.unciv.ui.components.widgets.ZoomableScrollable.scrollTo]. Does not itself trigger a
     *  relayout - the caller does that (see [RecyclerWorldMapHolder.scrollTo]). */
    fun centerOn(worldX: Float, worldY: Float) {
        val rv = recyclerView
        scrollOffsetX = worldX - (rv?.width ?: 0f) / 2f
        scrollOffsetY = worldY - (rv?.height ?: 0f) / 2f
    }

    /** [tile]'s current on-screen position - the same `tilePosition - scrollOffset` (plus
     *  [continuousScrollingX]'s periodic-copy choice) [onLayoutChildren]'s own reposition loop
     *  computes for every attached item, exposed here for callers that need a tile's screen position
     *  independent of whether that tile currently has a bound [RecyclerView.ViewHolder] at all - e.g.
     *  [RecyclerWorldMapHolder]'s arrow overlay, whose Actors are deliberately never tied to either
     *  endpoint's own pooled content (see that class's doc for why). Recomputed fresh every call,
     *  same as [worldPosition] itself - see that method's doc for why no caching is worth it. */
    fun screenPositionOf(tile: Tile): Vector2 {
        val rv = recyclerView
        val pos = worldPosition(tile)
        var x = pos.x - scrollOffsetX
        if (continuousScrollingX) x = closestPeriodicX(x, mapWidth, (rv?.width ?: 0f) / 2f)
        val y = pos.y - scrollOffsetY
        return Vector2(x, y)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val rv = recyclerView ?: return
        if (tileMap.tileList.isEmpty() || state.itemCount <= 0) {
            recycler.scrapActiveViews()
            recycler.recycleScrap()
            return
        }

        val viewportCenter = Vector2(scrollOffsetX + rv.width / 2f, scrollOffsetY + rv.height / 2f)
        val hexPosition = HexMath.world2HexCoords(pixelToHexCoord(viewportCenter))
        val rounded = HexMath.roundHexCoords(hexPosition)
        val roundedCoord = HexCoord(rounded.x.toInt(), rounded.y.toInt())
        // A hexagonal map isn't a rectangle: the exact rounded hex coordinate under the viewport's
        // geometric center can legitimately fall in the "cut corner" outside the map's actual
        // hex-shaped bounds - not just once at construction (scrollOffsetX/Y start at 0, which need
        // not land anywhere near the map), but repeatedly while panning, any time the viewport's own
        // geometric center happens to sweep through one of those corners. findNearestTile handles
        // that (see its own doc for why falling back to lastCenterTile alone isn't good enough - it
        // used to visibly "stick" for the whole width of a cut corner while panning, matching a real
        // bug report: a diagonal edge of missing tiles that self-healed once the center coordinate
        // wandered back onto real map territory).
        val centerTile = tileMap.getOrNull(roundedCoord.x, roundedCoord.y)
            ?: findNearestTile(roundedCoord)
            ?: lastCenterTile
            ?: tileMap.tileList.firstOrNull()
        if (centerTile == null) {
            recycler.scrapActiveViews()
            recycler.recycleScrap()
            return
        }

        val radius = maxOf(radiusToCoverViewport(rv.width, rv.height), MIN_RADIUS)
        // Cheap early-out: nothing to reassign if the same tiles would be selected again - only
        // recompute needed-tile membership when the anchor tile or radius actually changed. Every
        // visible position is still repositioned below on every call, regardless (see the class doc
        // on why: continuousScrollingX's periodic-copy choice can change even when membership doesn't).
        if (centerTile !== lastCenterTile || radius != lastRadius) {
            lastCenterTile = centerTile
            lastRadius = radius

            // radiusToCoverViewport's circle is a cheap, generous over-estimate by design (see its
            // own KDoc) - tileMap.forEachTileInDistance is what makes enumerating it a hex-ring walk
            // rather than an O(map size) scan, regardless of overall map size. But for anything but a
            // square viewport, a lot of that circle's area sits outside the actual on-screen
            // rectangle - every candidate is checked against the real viewport (padded by one tile's
            // worth of pixels, same reasoning as EDGE_MARGIN: a hex image bleeds outside its own cell)
            // before actually being bound, so those corners never cost a real WorldTileGroup/11
            // TileLayers. Skipping this filter was a real bug: a wide-aspect-ratio viewport bound
            // several times as many tiles as were ever visible on screen.
            //
            // The left/bottom edges each get one extra tile's worth of margin beyond that: a tile's
            // hex image isn't centered on its own tileX/tileY anchor (see TileGroup.hexagonImagePosition's
            // own doc) - it bleeds further past its anchor on those two sides than a single groupSize
            // of padding covers, which showed up as missing hex corners right at the left/bottom screen
            // edges specifically while panning in those directions (never top/right, which stayed
            // comfortably over-covered by the plain single-groupSize margin).
            val marginedLeft = scrollOffsetX - TileGroupMap.groupSize * 2
            val marginedRight = scrollOffsetX + rv.width + TileGroupMap.groupSize
            val marginedTop = scrollOffsetY - TileGroupMap.groupSize
            val marginedBottom = scrollOffsetY + rv.height + TileGroupMap.groupSize * 2
            val viewportCenterX = scrollOffsetX + rv.width / 2f

            val needed = HashSet<Tile>()
            tileMap.forEachTileInDistance(centerTile.position, radius) { tile ->
                val pos = worldPosition(tile)
                val x = if (continuousScrollingX) closestPeriodicX(pos.x, mapWidth, viewportCenterX) else pos.x
                if (x in marginedLeft..marginedRight && pos.y in marginedTop..marginedBottom) needed.add(tile)
            }

            recycler.scrapActiveViews()
            // Bracketing the attach loop in begin/endAttachBatch means every newly-bound tile's
            // wrapper Actors get inserted pre-sorted by Adapter.getViewComparator (HexTileAdapter's:
            // layer index primary, tile depth secondary - see its own doc) in one sort+merge pass
            // across the whole batch, instead of one O(childCount) scan per wrapper. An
            // already-attached wrapper reclaimed from scrap by getHolderForPosition below is left
            // exactly where it already sat (see that method's own doc) - not re-attached, so not
            // touched by the comparator at all - meaning an already-correctly-sorted attached set
            // never needs re-touching just because membership changed elsewhere. A previous version
            // of this instead re-derived the *entire* draw order from scratch on every membership
            // change via a manual full resort (`sortLayerMajor`) - correct, but redoing work this
            // batched, incremental attach makes unnecessary.
            recycler.beginAttachBatch()
            try {
                for (tile in needed) {
                    recycler.getHolderForPosition(tile.zeroBasedIndex) // binds/reuses; positioned below regardless
                }
            } finally {
                recycler.endAttachBatch()
            }
            recycler.recycleScrap()
        }

        // Reposition every currently-active tile - see the class doc on why this always runs, not
        // just when membership changed.
        for (position in recycler.getPositions().toList()) {
            val tile = tileMap.tileList.getOrNull(position) ?: continue
            val screenPos = screenPositionOf(tile)
            val x = screenPos.x
            val y = screenPos.y
            val holder = recycler.getHolderForPosition(position)
            // A holder whose item views aren't the whole story position-wise (see HexTileAdapter.
            // ViewHolder: its own WorldTileGroup is no longer part of the rendered scene graph, but
            // WorldMapHolder.addOverlayOnTileGroup/animateMovement still read its tileGroup.x/y
            // directly as "this tile's current screen position") opts into this extra sync call.
            (holder as? ExtraPositionSync)?.syncExtraPosition(x, y)
            for (view in holder.getItemViews()) view.setPosition(x, y)
        }
    }

    /**
     * Optional extra hook for a [RecyclerView.ViewHolder] whose [RecyclerView.ViewHolder.getItemViews]
     * aren't the only Actors [onLayoutChildren]'s reposition loop needs to keep in sync - see
     * [HexTileAdapter.ViewHolder] for why its own [com.unciv.ui.components.tilegroups.WorldTileGroup]
     * needs this despite not being one of its item views. A plain type check (rather than a
     * dependency on [HexTileAdapter.ViewHolder] specifically) so this class stays usable with any
     * [RecyclerView.Adapter] - including the bare-bones fake ones this class's own tests use.
     */
    interface ExtraPositionSync {
        fun syncExtraPosition(x: Float, y: Float)
    }

    companion object {
        /** Hex tile images extend beyond their own cell into neighboring cells' space - that's how
         *  hex maps avoid gaps between tiles at all. So covering a viewport rectangle needs tiles
         *  slightly beyond the strict distance-to-center radius too, or the *edges* of those
         *  outermost tiles' oversized images go unrendered - most noticeable as missing hex corners
         *  right at the edge of the screen while panning. Sized to comfortably reach
         *  [onLayoutChildren]'s own widest per-edge rectangle margin (the left/bottom edges get an
         *  extra tile's worth there - see that margin's own doc) - being a ring or two more generous
         *  than strictly needed on the other two edges costs nothing beyond enumerating a few extra
         *  candidates that [onLayoutChildren]'s rectangle check then filters back out. */
        private const val EDGE_MARGIN = 2
        private const val MIN_RADIUS = 2

        /** @see HexLayoutManager.findNearestTile */
        private const val NEAREST_TILE_SEARCH_RADIUS = 4

        /**
         * Smallest hex-ring radius, centered on the viewport's own geometric center, whose circle
         * fully contains the whole viewport rectangle - guarantees full coverage regardless of
         * aspect ratio or zoom level (the viewport shrinks/grows in world units as you zoom, so this
         * scales with it automatically). Using the rectangle's own half-diagonal - rather than half
         * width/height separately - sidesteps needing to know which axis is actually the limiting one.
         */
        fun radiusToCoverViewport(viewportWidth: Float, viewportHeight: Float): Int {
            val halfDiagonal = sqrt((viewportWidth / 2f).pow(2) + (viewportHeight / 2f).pow(2))
            // groupHorizontalAdvance is the smaller of the two per-ring world-space increments (the
            // other being groupSize), so dividing by it is the more conservative (larger) estimate.
            return ceil(halfDiagonal / TileGroupMap.groupHorizontalAdvance).toInt() + EDGE_MARGIN
        }

        /** Picks whichever of [rawX], `rawX - mapWidth`, or `rawX + mapWidth` is closest to [anchor]
         *  (the viewport's own on-screen center) - the periodic copy of a world-wrapped tile that
         *  actually belongs on screen right now, instead of the copy [rawX] alone would put possibly
         *  a whole map-width off to one side. */
        fun closestPeriodicX(rawX: Float, mapWidth: Float, anchor: Float): Float {
            var best = rawX
            var bestDist = abs(rawX - anchor)
            for (candidate in floatArrayOf(rawX - mapWidth, rawX + mapWidth)) {
                val dist = abs(candidate - anchor)
                if (dist < bestDist) { best = candidate; bestDist = dist }
            }
            return best
        }
    }
}
