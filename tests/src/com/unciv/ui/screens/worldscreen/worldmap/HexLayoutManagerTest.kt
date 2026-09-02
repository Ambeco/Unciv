package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.recyclerview.widget.RecyclerView
import com.unciv.ui.components.tilegroups.TileGroupMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [HexLayoutManager]'s pure math ([HexLayoutManager.radiusToCoverViewport],
 * [HexLayoutManager.closestPeriodicX]) plus its actual positioning/recycling behavior over a real
 * [TestGame] map, via a bare-bones fake adapter (plain sized [Actor]s, not real
 * [com.unciv.ui.components.tilegroups.WorldTileGroup]/[com.unciv.view.CivView]) - deliberately, so
 * these tests don't need a [com.unciv.ui.screens.worldscreen.WorldScreen]. Supersedes the old
 * `RecyclerWorldMapHolderPoolTest`, which covered the hand-rolled pool-selection math
 * ([RecyclerWorldMapHolder] had before it was rewritten to extend [RecyclerView] directly) that no
 * longer exists.
 */
@RunWith(GdxTestRunner::class)
class HexLayoutManagerTest {

    private lateinit var testGame: TestGame

    @Before
    fun setUp() {
        testGame = TestGame()
        // Radius has to comfortably exceed radiusToCoverViewport(300, 300) (~7 rings) - otherwise
        // the covering radius (deliberately generous - see that method's KDoc) would just cover the
        // *entire* map regardless of scroll position, defeating the point of the "on screen"/
        // "recycles" tests below (nothing would ever actually be off-screen or get recycled).
        testGame.makeHexagonalMap(25)
    }

    private class FakeViewHolder(size: Float) : RecyclerView.ViewHolder() {
        val actor = Actor().apply { setSize(size, size) }
        override fun getItemViews(): List<Actor> = listOf(actor)
    }

    private class FakeAdapter(private val allTiles: List<Tile>) : RecyclerView.Adapter<FakeViewHolder>() {
        override fun getItemCount() = allTiles.size
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = FakeViewHolder(TileGroupMap.groupSize)
        override fun onBindViewHolder(holder: FakeViewHolder, position: Int) {}
    }

    /** Mimics [HexTileAdapter.ViewHolder] exposing multiple stable per-layer Actors instead of one
     *  whole-tile Actor - the shape [HexLayoutManager.sortLayerMajor]'s test below needs, without
     *  needing a real [com.unciv.ui.components.tilegroups.WorldTileGroup]/11 real layers. */
    private class MultiItemFakeViewHolder(itemCount: Int) : RecyclerView.ViewHolder() {
        val items = List(itemCount) { Actor() }
        override fun getItemViews(): List<Actor> = items
    }

    private class MultiItemFakeAdapter(private val allTiles: List<Tile>, private val itemCount: Int) :
        RecyclerView.Adapter<MultiItemFakeViewHolder>() {
        // sortLayerMajor doesn't exist anymore - HexLayoutManager relies entirely on
        // Adapter.getViewComparator (via Recycler.beginAttachBatch/endAttachBatch) to keep attached
        // items layer-major, so this fake needs to supply one too. Only the item-index-ordering
        // invariant below this drives, so tile depth doesn't factor in here at all - see
        // HexTileAdapter.getViewComparator for what a real one looks like.
        private val itemIndexOf = HashMap<Actor, Int>()
        override fun getItemCount() = allTiles.size
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = MultiItemFakeViewHolder(itemCount).also { holder ->
            for ((itemIndex, actor) in holder.items.withIndex()) itemIndexOf[actor] = itemIndex
        }
        override fun onBindViewHolder(holder: MultiItemFakeViewHolder, position: Int) {}
        override fun getViewComparator(): Comparator<Actor> =
            Comparator { a, b -> itemIndexOf.getValue(a) - itemIndexOf.getValue(b) }
    }

    /** Index, in [makeRecycler]'s own `allTiles`, of the map's center tile (hex position (0,0)). */
    private fun centerIndex(allTiles: List<Tile>) = allTiles.indexOfFirst { it.position.x == 0 && it.position.y == 0 }

    private fun makeRecycler(width: Float = 300f, height: Float = 300f): Triple<RecyclerView, HexLayoutManager, List<Tile>> {
        val allTiles = testGame.tileMap.values.toList()
        val rv = RecyclerView()
        rv.setSize(width, height)
        val lm = HexLayoutManager(testGame.tileMap)
        rv.layoutManager = lm
        rv.setAdapter(FakeAdapter(allTiles))
        // Start centered on a real tile - scrollOffset (0,0) can otherwise legitimately land outside
        // a hexagonal map's actual (non-rectangular) bounds, in the "cut corner" of its bounding box
        // - see HexLayoutManager.onLayoutChildren's fallback doc - which isn't a meaningful starting
        // point for the tests below.
        rv.scrollToPosition(centerIndex(allTiles))
        rv.layout()
        return Triple(rv, lm, allTiles)
    }

    // region radiusToCoverViewport

    @Test
    fun `radiusToCoverViewport grows with viewport size`() {
        val small = HexLayoutManager.radiusToCoverViewport(200f, 200f)
        val large = HexLayoutManager.radiusToCoverViewport(2000f, 2000f)
        assertTrue("a 10x larger viewport should need a larger covering radius", large > small)
    }

    @Test
    fun `radiusToCoverViewport is always at least 1 (the edge margin) even for a zero-size viewport`() {
        assertTrue(HexLayoutManager.radiusToCoverViewport(0f, 0f) >= 1)
    }

    // endregion
    // region closestPeriodicX

    @Test
    fun `closestPeriodicX leaves a position alone when it is already the closest copy`() {
        assertEquals(50f, HexLayoutManager.closestPeriodicX(50f, mapWidth = 1000f, anchor = 60f), 0.001f)
    }

    @Test
    fun `closestPeriodicX wraps to the copy nearest the anchor`() {
        // Anchor near the right edge of a 1000-wide wrap; a tile positioned near the left edge
        // (x=10) is actually closer to the anchor via its +mapWidth copy (1010) than its raw position.
        val result = HexLayoutManager.closestPeriodicX(10f, mapWidth = 1000f, anchor = 990f)
        assertEquals(1010f, result, 0.001f)
    }

    // endregion
    // region positioning / recycling over a real map

    @Test
    fun `after layout, the tile under the viewport's own center is attached`() {
        val (rv, _, allTiles) = makeRecycler()
        assertTrue("expected at least one attached tile", rv.recycler.getPositions().isNotEmpty())
        assertTrue(centerIndex(allTiles) in rv.recycler.getPositions())
    }

    /**
     * Unlike [LinearLayoutManagerTest]/[GridLayoutManagerTest]'s identically-purposed tests, this
     * doesn't assert every attached tile overlaps the viewport rectangle either - even after
     * [onLayoutChildren]'s own rectangle filter (see its doc), a corner candidate that just barely
     * misses being filtered out (within the margin, but its *own* far corner still lands outside the
     * strict rectangle - a hex image is larger than its cell) is still legitimately attached, so
     * the invariant would have to be "near", not "overlaps" - see the margined-rectangle test below
     * for that looser check instead. What's checked here: a tile already on screen before a small scroll stays
     * attached and lands exactly where the scroll delta says it should - i.e. nothing on screen is
     * ever dropped or mispositioned.
     */
    @Test
    fun `a small scroll keeps the previously-centered tile attached and moves it by the scroll delta`() {
        val (rv, _, allTiles) = makeRecycler()
        val index = centerIndex(allTiles)
        val before = rv.findViewHolderForAdapterPosition(index)!!.getItemViews()[0]
        val beforeX = before.x
        val beforeY = before.y

        rv.scrollBy(37f, -53f) // arbitrary, not aligned to any tile/grid boundary
        rv.layout()

        assertTrue("the previously-centered tile should still be attached after a small scroll",
            index in rv.recycler.getPositions())
        val after = rv.findViewHolderForAdapterPosition(index)!!.getItemViews()[0]
        // Scrolling by (dx, dy) moves every on-screen item by (-dx, -dy) - see
        // HexLayoutManager.scrollHorizontallyBy/scrollVerticallyBy's KDoc.
        assertEquals(beforeX - 37f, after.x, 0.001f)
        assertEquals(beforeY + 53f, after.y, 0.001f)
    }

    /**
     * Regression test for a real bug: [onLayoutChildren] used to bind every candidate
     * [radiusToCoverViewport]'s covering *circle* enumerated, without checking whether each one
     * actually fell inside the on-screen rectangle - for a wide-aspect-ratio viewport, the circle's
     * area can be several times the rectangle's, so this bound (and kept alive) several times as many
     * [com.unciv.ui.components.tilegroups.WorldTileGroup]s/
     * [com.unciv.ui.components.tilegroups.layers.TileLayer]s as were ever visible. Computed via a
     * delta from the already-positioned center tile's own actor (like the cut-corner test above), so
     * the private world-space normalization this class keeps never needs to leak out to the test.
     */
    @Test
    fun `every attached tile lands within one tile's margin of the viewport rectangle`() {
        val (rv, _, allTiles) = makeRecycler()
        val centerIdx = centerIndex(allTiles)
        val centerView = rv.findViewHolderForAdapterPosition(centerIdx)!!.getItemViews()[0]
        val centerRaw = com.unciv.logic.map.HexMath.hex2WorldCoords(allTiles[centerIdx].position)
            .scl(0.8f * TileGroupMap.groupSize)

        val margin = TileGroupMap.groupSize
        for (position in rv.recycler.getPositions()) {
            val raw = com.unciv.logic.map.HexMath.hex2WorldCoords(allTiles[position].position)
                .scl(0.8f * TileGroupMap.groupSize)
            val screenX = centerView.x + (raw.x - centerRaw.x)
            val screenY = centerView.y + (raw.y - centerRaw.y)
            assertTrue(
                "tile at position $position landed at ($screenX, $screenY), outside the viewport " +
                    "rectangle (0..${rv.width}, 0..${rv.height}) plus a $margin margin",
                screenX >= -margin && screenX <= rv.width + margin && screenY >= -margin && screenY <= rv.height + margin
            )
        }
    }

    // endregion
    // region restrictX/restrictY center-vs-edge conversion

    /** Mirrors [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder.getScrollX]'s exact
     *  contract: the viewport's world-space *center*, not [HexLayoutManager.scrollOffsetX]'s own
     *  left-edge convention. */
    private fun scrollXCenter(rv: RecyclerView, lm: HexLayoutManager) = lm.scrollOffsetX + rv.width / 2f

    /** Mirrors [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder.getScrollY]'s exact
     *  contract: a `mapHeight`-inverted viewport center, not [HexLayoutManager.scrollOffsetY]'s own
     *  bottom-edge convention. */
    private fun scrollYCenter(rv: RecyclerView, lm: HexLayoutManager) = lm.mapHeight - (lm.scrollOffsetY + rv.height / 2f)

    /**
     * Regression test for a real bug: [restrictX]/[restrictY] (as
     * [com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder.restrictX]/`restrictY` are actually
     * wired up in production) operate in the *center* terms above, not [scrollOffsetX]'s own
     * left-edge terms - assigning a restrictX/restrictY result straight to scrollOffsetX/Y without
     * converting between the two (and, for Y, without also flipping the sign - `getScrollY`'s
     * `mapHeight -` inversion means its own delta convention runs opposite to scrollOffsetY's) added
     * up to hundreds of pixels of bogus extra scroll on every single drag frame in-game - "dragging
     * incredible distances" from a tiny mouse movement, per the underlying bug report. An *unclamped*
     * restrictX/restrictY (no explored-region clamp triggered) must be behaviorally identical to no
     * restrictX/restrictY at all - scrolling by exactly `dx`/`dy`, nothing more.
     */
    @Test
    fun `an unclamped, center-based restrictX still scrolls by exactly dx`() {
        val (rv, lm, _) = makeRecycler()
        lm.restrictX = { deltaX -> scrollXCenter(rv, lm) - deltaX } // WorldMapHolder.restrictX's real formula
        val before = lm.scrollOffsetX

        rv.scrollBy(5f, 0f)

        assertEquals("a non-clamping, center-based restrictX must behave identically to no restrictX at all",
            before + 5f, lm.scrollOffsetX, 0.001f)
    }

    @Test
    fun `an unclamped, center-based restrictY still scrolls by exactly dy`() {
        val (rv, lm, _) = makeRecycler()
        lm.restrictY = { deltaY -> scrollYCenter(rv, lm) + deltaY } // WorldMapHolder.restrictY's real formula
        val before = lm.scrollOffsetY

        rv.scrollBy(0f, 5f)

        assertEquals("a non-clamping, center-based restrictY must behave identically to no restrictY at all",
            before + 5f, lm.scrollOffsetY, 0.001f)
    }

    // endregion
    // region positioning / recycling over a real map (continued)

    @Test
    fun `scrolling far across the map recycles previously-attached tiles`() {
        val (rv, _, allTiles) = makeRecycler()
        val positionsBefore = rv.recycler.getPositions().toSet()

        // Jump to the tile furthest from the map's center - a real tile, unlike an arbitrary pixel
        // delta, which could easily overshoot a hexagonal map's non-rectangular bounds into the same
        // "cut corner" void covered above (landing back on the last-known-good anchor - i.e. nothing
        // would actually change, defeating this test).
        val origin = allTiles[centerIndex(allTiles)].position
        val farIndex = allTiles.indices.maxBy { com.unciv.logic.map.HexMath.getDistance(allTiles[it].position, origin) }
        rv.scrollToPosition(farIndex)
        rv.layout()

        val positionsAfter = rv.recycler.getPositions().toSet()
        assertTrue("expected the attached set to actually change after scrolling clear across the map",
            positionsAfter.intersect(positionsBefore).size < positionsBefore.size)
    }

    /**
     * Regression test for a real bug: a hexagonal map's bounding box has "cut corner" regions with
     * no real tiles at all - [onLayoutChildren]'s old fallback (`getOrNull(...) ?: lastCenterTile`)
     * would *stick* on whatever tile was last valid for as long as the viewport's geometric center
     * stayed inside one of those corners, even though the live scroll offset (and therefore every
     * tile's on-screen position) kept changing underneath it - the attached pool simply stopped
     * updating for the width of the corner, then "popped back in" once the center wandered back onto
     * real map territory. [findNearestTile] exists specifically to search outward instead of sticking.
     *
     * A weaker "the pool isn't empty" assertion wouldn't actually catch the old bug: the old
     * fallback chain also ended in `lastCenterTile`/`allTiles.firstOrNull()`, so it was never
     * literally *empty*, just *stuck* far from the true target - so this checks the pool actually
     * ends up *near* the (deliberately unreachable) target instead.
     *
     * [makeRecycler] already centers on the map's own origin tile; this then jumps toward a
     * coordinate well outside the radius-25 map's bounds (guaranteed no real tile exists there) -
     * *relative* to the origin, so only [HexMath.hex2WorldCoords]'s public formula is needed (the
     * constant normalization term [HexLayoutManager] keeps private cancels out of a pure delta).
     */
    @Test
    fun `the attached pool moves near the target even when centered exactly on a hexagonal map's cut corner`() {
        val (rv, _, allTiles) = makeRecycler()
        // Just past the radius-25 map's edge (see setUp) - guaranteed no real tile exists exactly
        // here, but real tiles are only a hex-ring or so away (a genuine "cut corner", unlike a
        // target so far off-map that even the nearest real tile is legitimately unreachable and
        // sticking to the last-known-good position would be the *correct* behavior instead of a bug).
        val invalidCoord = com.unciv.logic.map.HexCoord(26, 0)
        check(com.unciv.logic.map.HexMath.getDistance(invalidCoord, com.unciv.logic.map.HexCoord.Zero) > 25) {
            "test setup's 'invalid' coordinate is actually within the map"
        }

        val originRaw = com.unciv.logic.map.HexMath.hex2WorldCoords(com.unciv.logic.map.HexCoord.Zero).scl(0.8f * TileGroupMap.groupSize)
        val invalidRaw = com.unciv.logic.map.HexMath.hex2WorldCoords(invalidCoord).scl(0.8f * TileGroupMap.groupSize)
        rv.scrollBy(invalidRaw.x - originRaw.x, invalidRaw.y - originRaw.y) // relative delta - centers on invalidCoord
        rv.layout()

        val minDistanceToTarget = rv.recycler.getPositions().minOf { position ->
            com.unciv.logic.map.HexMath.getDistance(allTiles[position].position, invalidCoord)
        }
        assertTrue("expected the attached pool to have moved near the cut-corner target (closest attached tile " +
            "was $minDistanceToTarget hexes away) instead of staying stuck near the map's own center",
            minDistanceToTarget < 10)
    }

    // endregion
    // region layer-major sort

    /**
     * Regression test for a real bug: since each tile used to be one self-contained Actor (all its
     * layers drawn as one atomic front-to-back unit), a city button on one tile could be drawn over
     * by *terrain* from a different, "later" tile - tile-major order can never express "every tile's
     * city button is above every tile's terrain", only "everything about tile A is above everything
     * about tile B". [HexLayoutManager]'s sort (triggered whenever attached-tile membership changes -
     * see [onLayoutChildren]) fixes this by stacking per-item-index instead: every attached tile's
     * item 0 (its [HexTileAdapter.ViewHolder]'s first layer wrapper) ends up below every attached
     * tile's item 1, which ends up below every item 2, etc., regardless of which tile owns which.
     *
     * Verified generically, without any real per-layer meaning: [MultiItemFakeAdapter] hands out
     * [itemCount] plain Actors per tile, and this checks the *only* invariant [HexLayoutManager]
     * itself is responsible for - that [RecyclerView]'s children end up grouped by item index, in
     * ascending order - not anything about what a "layer" actually draws.
     */
    @Test
    fun `attached tiles are stacked layer-major - every tile's item N above every tile's item less-than-N`() {
        val allTiles = testGame.tileMap.values.toList()
        val rv = RecyclerView()
        rv.setSize(300f, 300f)
        val lm = HexLayoutManager(testGame.tileMap)
        rv.layoutManager = lm
        val itemCount = 3
        rv.setAdapter(MultiItemFakeAdapter(allTiles, itemCount))
        rv.scrollToPosition(centerIndex(allTiles))
        rv.layout()

        val positions = rv.recycler.getPositions()
        assertTrue("need at least 2 attached tiles for a meaningful cross-tile ordering check", positions.size > 1)

        val itemIndexOf = HashMap<Actor, Int>()
        for (position in positions) {
            rv.recycler.getViewsForPosition(position).forEachIndexed { itemIndex, actor -> itemIndexOf[actor] = itemIndex }
        }
        val childItemIndices = rv.children.mapNotNull { itemIndexOf[it] }
        assertEquals("expected every attached tile's every item as a direct RecyclerView child",
            positions.size * itemCount, childItemIndices.size)
        assertEquals("expected children sorted layer-major (all item-0s, then all item-1s, ...), but got $childItemIndices",
            childItemIndices.sorted(), childItemIndices)
    }

    // endregion
}
