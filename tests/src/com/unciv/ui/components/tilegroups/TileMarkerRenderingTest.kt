package com.unciv.ui.components.tilegroups

import com.unciv.dev.FontDesktop
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.view.GameView
import com.unciv.view.TileMarker
import com.unciv.view.TileSingleAnimation
import org.junit.Assert.assertEquals
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
    fun `a highlight survives its WorldTileGroup being recycled away and back`() {
        val tileView = gameView.tileMapView.getTile(testGame.getTile(0, 0))
        tileView.addMarker(TileMarker.ATTACKABLE)

        val group = tileGroupFor(0, 0)
        group.update(gameView.civView)
        assertTrue(group.layerOverlay.isVisible)

        // Recycled away to an unrelated, unmarked tile - standing in for what HexTileAdapter.onBindViewHolder
        // does when a position scrolls off and a different one needs the same pooled slot.
        val otherTile = testGame.tileMap.values.first { it.position != tileView.getTile().position }
        group.rebind(gameView.tileMapView.getTile(otherTile), 0f, 0f, gameView.civView)
        assertFalse("no marker on the tile it was recycled to", group.layerOverlay.isVisible)

        // Recycled back - the marker is still on the *tile*, not the (long since wiped) WorldTileGroup,
        // so it must reappear with no external "reapply" call needed.
        group.rebind(tileView, 0f, 0f, gameView.civView)
        assertTrue("the marker on tile (0,0) should still be there, and be picked up again on rebind",
            group.layerOverlay.isVisible)
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

    @Test
    fun `a one-shot animation survives its WorldTileGroup being recycled away and back mid-flight`() {
        val tileView = gameView.tileMapView.getTile(testGame.getTile(0, 0))
        tileView.playAnimation(TileSingleAnimation.NUKE_BLAST)

        val group = tileGroupFor(0, 0)
        group.update(gameView.civView)
        assertTrue("the animation just started, well within its duration - should be showing",
            group.layerOverlay.isVisible)

        // Recycled away mid-flight to an unrelated tile with no animation of its own - standing in
        // for what HexTileAdapter.onBindViewHolder does when a position scrolls off and a different
        // one needs the same pooled slot, same as the marker test above.
        val otherTile = testGame.tileMap.values.first { it.position != tileView.getTile().position }
        group.rebind(gameView.tileMapView.getTile(otherTile), 0f, 0f, gameView.civView)
        assertFalse("no animation on the tile it was recycled to", group.layerOverlay.isVisible)

        // Recycled back before the animation's duration has elapsed - the (type, start time) is still
        // on the *tile*, not the (long since wiped) WorldTileGroup, so playback must resume rather
        // than staying dropped or restarting from scratch.
        group.rebind(tileView, 0f, 0f, gameView.civView)
        assertTrue("the animation on tile (0,0) hasn't finished yet, so it should resume on rebind",
            group.layerOverlay.isVisible)
        assertEquals("still playing, so TileView shouldn't have cleared it itself",
            TileSingleAnimation.NUKE_BLAST, tileView.tileSingleAnimation)
    }

    @Test
    fun `a one-shot animation is cleared once its duration has elapsed`() {
        val tileView = gameView.tileMapView.getTile(testGame.getTile(0, 0))
        tileView.playAnimation(TileSingleAnimation.NUKE_BLAST)

        // Backdate the start time past the animation's own duration, rather than sleeping the test -
        // playAnimation() always stamps "now", so there's no other way to simulate elapsed time.
        val pastStart = System.currentTimeMillis() - (TileSingleAnimation.NUKE_BLAST.totalDurationSeconds * 1000).toLong() - 100L
        setAnimationStartTime(tileView, pastStart)

        val group = tileGroupFor(0, 0)
        group.update(gameView.civView)

        assertFalse("the animation's duration has fully elapsed - nothing should be showing",
            group.layerOverlay.isVisible)
        assertEquals("doUpdate() should have cleared it itself, with no external call needed",
            null, tileView.tileSingleAnimation)
    }

    /** [com.unciv.view.TileView.tileSingleAnimationStartTime] is only ever set "to now" by
     *  [com.unciv.view.TileView.playAnimation] - reflection is the only way to backdate it for a test. */
    private fun setAnimationStartTime(tileView: com.unciv.view.TileView, timeMillis: Long) {
        val field = tileView.javaClass.getDeclaredField("tileSingleAnimationStartTime")
        field.isAccessible = true
        field.setLong(tileView, timeMillis)
    }
}
