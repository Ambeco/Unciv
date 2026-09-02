package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.logic.map.tile.Tile
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.MiscArrowTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [ArrowLifecycle] in isolation - no [com.unciv.ui.components.tilegroups.WorldTileGroup]/
 * `CivView`/[com.unciv.ui.components.recyclerview.widget.RecyclerView] needed, just real [Tile]s
 * (for [ArrowLifecycle.add]'s self-arrow position check) and a plain in-test "is this tile bound"
 * set the test controls directly, standing in for [RecyclerWorldMapHolder]'s actual pool.
 */
@RunWith(GdxTestRunner::class)
class ArrowLifecycleTest {

    private lateinit var testGame: TestGame
    private lateinit var tiles: List<Tile>

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(3)
        tiles = testGame.tileMap.values.toList()
    }

    private val activated = mutableListOf<ArrowLifecycle.ArrowSpec>()
    private val deactivated = mutableListOf<ArrowLifecycle.ArrowSpec>()
    private val bound = HashSet<Tile>()

    private fun makeLifecycle() = ArrowLifecycle(
        onActivated = { activated.add(it) },
        onDeactivated = { deactivated.add(it) }
    )

    @Test
    fun `adding an arrow with neither endpoint bound stays inactive`() {
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        assertTrue(activated.isEmpty())
    }

    @Test
    fun `adding an arrow whose source is already bound activates it immediately`() {
        bound.add(tiles[0])
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        assertEquals(1, activated.size)
        assertEquals(1, activated[0].boundEndpoints)
    }

    @Test
    fun `adding an arrow whose target is already bound activates it immediately too`() {
        bound.add(tiles[1])
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        assertEquals(1, activated.size)
    }

    @Test
    fun `an arrow between two tiles at the same position is never tracked at all`() {
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[0], MiscArrowTypes.UnitMoving) { true }
        assertTrue(activated.isEmpty())
        lifecycle.onTileBound(tiles[0])
        assertTrue("a self-arrow must never have been registered under its own tile at all", activated.isEmpty())
    }

    @Test
    fun `binding the second endpoint of an already-active arrow does not reactivate it`() {
        bound.add(tiles[0])
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        assertEquals(1, activated.size)

        lifecycle.onTileBound(tiles[1])
        assertEquals("the second endpoint binding must only bump the count, not activate again",
            1, activated.size)
        assertEquals(2, activated[0].boundEndpoints)
    }

    @Test
    fun `binding the first endpoint of a not-yet-active arrow activates it exactly once`() {
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        assertTrue(activated.isEmpty())

        lifecycle.onTileBound(tiles[0])
        assertEquals(1, activated.size)
    }

    @Test
    fun `unbinding the only bound endpoint deactivates the arrow`() {
        bound.add(tiles[0])
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }

        lifecycle.onTileUnbound(tiles[0])
        assertEquals(1, deactivated.size)
        assertEquals(0, deactivated[0].boundEndpoints)
    }

    @Test
    fun `an arrow survives its source scrolling off as long as its target is still bound`() {
        bound.add(tiles[0])
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        lifecycle.onTileBound(tiles[1]) // target scrolls into view too - boundEndpoints now 2

        lifecycle.onTileUnbound(tiles[0]) // source scrolls off
        assertTrue("the arrow must still be alive - its target endpoint is still bound", deactivated.isEmpty())

        lifecycle.onTileUnbound(tiles[1]) // now both are gone
        assertEquals(1, deactivated.size)
    }

    @Test
    fun `two arrows sharing an endpoint are tracked independently`() {
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        lifecycle.add(tiles[0], tiles[2], MiscArrowTypes.UnitHasAttacked) { it in bound }

        lifecycle.onTileBound(tiles[0])
        assertEquals("both arrows share tiles[0] as an endpoint, so both should activate",
            2, activated.size)

        lifecycle.onTileUnbound(tiles[0])
        assertEquals(2, deactivated.size)
    }

    @Test
    fun `reset deactivates every still-active arrow and forgets it entirely`() {
        bound.add(tiles[0])
        val lifecycle = makeLifecycle()
        lifecycle.add(tiles[0], tiles[1], MiscArrowTypes.UnitMoving) { it in bound }
        lifecycle.add(tiles[2], tiles[1], MiscArrowTypes.UnitMoving) { it in bound } // inactive - neither endpoint bound

        lifecycle.reset()
        assertEquals("only the one active arrow should have been deactivated", 1, deactivated.size)

        // Forgotten entirely - a later bind of any of these tiles must not resurrect it.
        deactivated.clear()
        lifecycle.onTileBound(tiles[0])
        lifecycle.onTileBound(tiles[1])
        lifecycle.onTileBound(tiles[2])
        assertEquals("no new activation - reset forgot every spec, so these binds found nothing",
            1, activated.size) // the 1 is the original add()'s own activation, from before reset
        assertTrue(deactivated.isEmpty())
    }
}
