package com.unciv.view

import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [TileView.overlays]/[TileView.selectedUnitForFlag] and [TileMapView]'s reset-tracking of
 * them - the data layer [com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater] writes to
 * and every [com.unciv.ui.components.tilegroups.layers.TileLayer]'s `doUpdate()` reads back, kept
 * independent of any pooled/recycled rendering object - see [TileView.overlays]'s own doc.
 */
@RunWith(GdxTestRunner::class)
class TileOverlayTest {

    private lateinit var testGame: TestGame
    private lateinit var tileMapView: TileMapView

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(2)
        tileMapView = TileMapView(testGame.tileMap, viewer = null)
    }

    @Test
    fun `a freshly cached TileView has no overlays set`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        assertEquals(0, tileView.overlays)
        assertFalse(tileView.hasOverlay(TileOverlay.SELECTED))
    }

    @Test
    fun `addOverlay sets the bit and hasOverlay reads it back`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addOverlay(TileOverlay.SELECTED)
        assertTrue(tileView.hasOverlay(TileOverlay.SELECTED))
        assertFalse("an unrelated bit must not have been set too", tileView.hasOverlay(TileOverlay.ATTACKABLE))
    }

    @Test
    fun `multiple addOverlay calls accumulate rather than overwrite`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addOverlay(TileOverlay.MOVABLE_TO)
        tileView.addOverlay(TileOverlay.MOVABLE_TO_PARADROP)
        assertTrue(tileView.hasOverlay(TileOverlay.MOVABLE_TO))
        assertTrue(tileView.hasOverlay(TileOverlay.MOVABLE_TO_PARADROP))
    }

    @Test
    fun `resetOverlays clears every overlay that was set`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addOverlay(TileOverlay.SELECTED)
        tileView.addOverlay(TileOverlay.ATTACKABLE)

        tileMapView.resetOverlays()

        assertEquals(0, tileView.overlays)
    }

    @Test
    fun `resetOverlays does not touch a tile that never had an overlay set`() {
        val tiles = testGame.tileMap.values.toList()
        val markedView = tileMapView.getTile(tiles[0])
        val untouchedView = tileMapView.getTile(tiles[1])
        markedView.addOverlay(TileOverlay.SELECTED)

        tileMapView.resetOverlays()

        assertEquals(0, markedView.overlays)
        assertEquals(0, untouchedView.overlays) // was never set in the first place, not "reset"
    }

    @Test
    fun `a tile can have an overlay set again after being reset`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addOverlay(TileOverlay.SELECTED)
        tileMapView.resetOverlays()

        tileView.addOverlay(TileOverlay.ATTACKABLE)
        assertTrue(tileView.hasOverlay(TileOverlay.ATTACKABLE))
        assertFalse(tileView.hasOverlay(TileOverlay.SELECTED))
    }

    @Test
    fun `setSelectedUnitForFlag is tracked and cleared by resetOverlays just like overlays`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        val unit = testGame.addUnit("Warrior", testGame.addCiv(), tileView.getTile())

        tileView.setSelectedUnitForFlag(unit)
        assertEquals(unit, tileView.selectedUnitForFlag)

        tileMapView.resetOverlays()
        assertNull(tileView.selectedUnitForFlag)
    }

    @Test
    fun `addOverlay and setSelectedUnitForFlag on the same tile are both cleared by one reset`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        val unit = testGame.addUnit("Warrior", testGame.addCiv(), tileView.getTile())
        tileView.addOverlay(TileOverlay.SELECTED)
        tileView.setSelectedUnitForFlag(unit)

        tileMapView.resetOverlays()

        assertEquals(0, tileView.overlays)
        assertNull(tileView.selectedUnitForFlag)
    }
}
