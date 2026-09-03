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
 * Covers [TileView.markers]/[TileView.selectedUnitForFlag] and [TileMapView]'s reset-tracking of
 * them - the data layer [com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater] writes to
 * and every [com.unciv.ui.components.tilegroups.layers.TileLayer]'s `doUpdate()` reads back, kept
 * independent of any pooled/recycled rendering object - see [TileView.markers]'s own doc.
 */
@RunWith(GdxTestRunner::class)
class TileMarkerTest {

    private lateinit var testGame: TestGame
    private lateinit var tileMapView: TileMapView

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(2)
        tileMapView = TileMapView(testGame.tileMap, viewer = null)
    }

    @Test
    fun `a freshly cached TileView has no markers set`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        assertEquals(0, tileView.markers)
        assertFalse(tileView.hasMarker(TileMarker.SELECTED))
    }

    @Test
    fun `addMarker sets the bit and hasMarker reads it back`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addMarker(TileMarker.SELECTED)
        assertTrue(tileView.hasMarker(TileMarker.SELECTED))
        assertFalse("an unrelated bit must not have been set too", tileView.hasMarker(TileMarker.ATTACKABLE))
    }

    @Test
    fun `multiple addMarker calls accumulate rather than overwrite`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addMarker(TileMarker.MOVABLE_TO)
        tileView.addMarker(TileMarker.MOVABLE_TO_PARADROP)
        assertTrue(tileView.hasMarker(TileMarker.MOVABLE_TO))
        assertTrue(tileView.hasMarker(TileMarker.MOVABLE_TO_PARADROP))
    }

    @Test
    fun `resetMarkers clears every marker that was set`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addMarker(TileMarker.SELECTED)
        tileView.addMarker(TileMarker.ATTACKABLE)

        tileMapView.resetMarkers()

        assertEquals(0, tileView.markers)
    }

    @Test
    fun `resetMarkers does not touch a tile that never had a marker set`() {
        val tiles = testGame.tileMap.values.toList()
        val markedView = tileMapView.getTile(tiles[0])
        val untouchedView = tileMapView.getTile(tiles[1])
        markedView.addMarker(TileMarker.SELECTED)

        tileMapView.resetMarkers()

        assertEquals(0, markedView.markers)
        assertEquals(0, untouchedView.markers) // was never set in the first place, not "reset"
    }

    @Test
    fun `a tile can be marked again after being reset`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        tileView.addMarker(TileMarker.SELECTED)
        tileMapView.resetMarkers()

        tileView.addMarker(TileMarker.ATTACKABLE)
        assertTrue(tileView.hasMarker(TileMarker.ATTACKABLE))
        assertFalse(tileView.hasMarker(TileMarker.SELECTED))
    }

    @Test
    fun `setSelectedUnitForFlag is tracked and cleared by resetMarkers just like markers`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        val unit = testGame.addUnit("Warrior", testGame.addCiv(), tileView.getTile())

        tileView.setSelectedUnitForFlag(unit)
        assertEquals(unit, tileView.selectedUnitForFlag)

        tileMapView.resetMarkers()
        assertNull(tileView.selectedUnitForFlag)
    }

    @Test
    fun `addMarker and setSelectedUnitForFlag on the same tile are both cleared by one reset`() {
        val tileView = tileMapView.getTile(testGame.tileMap.values.first())
        val unit = testGame.addUnit("Warrior", testGame.addCiv(), tileView.getTile())
        tileView.addMarker(TileMarker.SELECTED)
        tileView.setSelectedUnitForFlag(unit)

        tileMapView.resetMarkers()

        assertEquals(0, tileView.markers)
        assertNull(tileView.selectedUnitForFlag)
    }
}
