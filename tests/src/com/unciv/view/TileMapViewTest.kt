package com.unciv.view

import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [TileMapView]'s [TileView]-caching guarantee: every [TileView] any caller gets for a given
 * [com.unciv.logic.map.tile.Tile] must be the *same* instance, regardless of which overload of
 * `getTile` was used to reach it. This isn't just a minor optimization - it's what makes it safe to
 * store mutable per-tile UI state (e.g. current highlight/overlay markers) directly as fields on a
 * [TileView] rather than in some separate Tile-keyed map: two *different* [TileView] objects for the
 * same tile already compare equal ([View.equals]/[View.hashCode] are structural, by the wrapped
 * [com.unciv.logic.map.tile.Tile]), but they don't *share* any mutable field state unless they're
 * actually the same object.
 */
@RunWith(GdxTestRunner::class)
class TileMapViewTest {

    private lateinit var testGame: TestGame
    private lateinit var tileMapView: TileMapView

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(2)
        tileMapView = TileMapView(testGame.tileMap, viewer = null)
    }

    @Test
    fun `getTile(Tile) returns the same instance on repeated calls`() {
        val tile = testGame.tileMap.values.first()
        assertSame(tileMapView.getTile(tile), tileMapView.getTile(tile))
    }

    @Test
    fun `getTile(HexCoord) returns the same instance as getTile(Tile) for the same tile`() {
        val tile = testGame.tileMap.values.first()
        val byTile = tileMapView.getTile(tile)
        val byPosition = tileMapView.getTile(tile.position)
        assertSame("getTile(position) must route through the same cache getTile(tile) uses - " +
            "otherwise mutable per-tile state stored on a TileView instance wouldn't be visible " +
            "through both access paths", byTile, byPosition)
    }

    @Test
    fun `getTile(HexCoord) returns the same instance across repeated calls too`() {
        val tile = testGame.tileMap.values.first()
        assertSame(tileMapView.getTile(tile.position), tileMapView.getTile(tile.position))
    }
}
