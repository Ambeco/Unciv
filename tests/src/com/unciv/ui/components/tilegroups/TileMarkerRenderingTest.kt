package com.unciv.ui.components.tilegroups

import com.unciv.dev.FontDesktop
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.view.GameView
import com.unciv.view.TileMarker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the actual bug [com.unciv.view.TileView.markers] exists to fix: a highlight set on a tile
 * must survive that tile's [WorldTileGroup] being recycled away to a different tile and back - see
 * that property's own doc. Structural (via [com.unciv.ui.components.tilegroups.layers.TileLayer.isVisible]),
 * same reasoning [TileGroupRebindTest]'s own doc gives for why: most of the per-layer Image state is
 * `internal`, invisible from this separate `tests` module.
 */
@RunWith(GdxTestRunner::class)
class TileMarkerRenderingTest {

    private lateinit var testGame: TestGame
    private lateinit var tileSetStrings: TileSetStrings
    private lateinit var gameView: GameView

    @Before
    fun setUp() {
        testGame = TestGame()
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(testGame.ruleset)
        TileSetCache.loadTileSetConfigs()
        tileSetStrings = TileSetStrings()

        testGame.makeRectangularMap(5, 5)
        val civ = testGame.addCiv(isPlayer = true)
        gameView = GameView(testGame.gameInfo, civ)

        // Explored, so TileLayerOverlay's own "unexplored" fog-of-war graphic never shows - it isn't
        // gated by isForceVisible (unlike ordinary fog) and would otherwise confound isVisible as a
        // stand-in for "is a highlight/crosshair/etc. currently showing" below.
        for (tile in testGame.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tileGroupFor(x: Int, y: Int): WorldTileGroup {
        val tile = testGame.getTile(x, y)
        val group = WorldTileGroup(gameView.tileMapView.getTile(tile), tileSetStrings)
        group.isForceVisible = true
        return group
    }

    @Test
    fun `a marker set before a tile is ever bound is picked up on its first bind`() {
        val tileView = gameView.tileMapView.getTile(testGame.getTile(0, 0))
        tileView.addMarker(TileMarker.SELECTED)

        val group = tileGroupFor(0, 0)
        group.update(gameView.civView)

        assertTrue("the highlight layer should be visible - SELECTED was set before this tile's " +
            "WorldTileGroup was ever bound", group.layerOverlay.isVisible)
    }

    @Test
    fun `resetMarkers clears a highlight on the next update`() {
        val tileView = gameView.tileMapView.getTile(testGame.getTile(0, 0))
        tileView.addMarker(TileMarker.SELECTED)
        val group = tileGroupFor(0, 0)
        group.update(gameView.civView)
        assertTrue(group.layerOverlay.isVisible)

        gameView.tileMapView.resetMarkers()
        group.update(gameView.civView)

        assertFalse(group.layerOverlay.isVisible)
    }
}
