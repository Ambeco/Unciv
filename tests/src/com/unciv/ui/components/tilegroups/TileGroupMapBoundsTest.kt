package com.unciv.ui.components.tilegroups

import com.unciv.dev.FontDesktop
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TileGroupMap]'s `allTiles` parameter lets a caller size/scroll the map for tiles that don't
 * currently have a live [TileGroup] - what a pooled/recycling map holder needs (a fixed, small pool
 * of [TileGroup]s reused as the viewport scrolls) without the map itself shrinking to fit whatever
 * subset happens to be pooled right now. These tests check that:
 *  - omitting `allTiles` (every caller today) is exactly as before - bounds come from `tileGroups` alone;
 *  - passing `allTiles` widens bounds to the full tile set even when `tileGroups` is a small subset;
 *  - [TileGroupMap.getTileGroupOrNull] only ever returns groups actually passed in `tileGroups`.
 */
@RunWith(GdxTestRunner::class)
class TileGroupMapBoundsTest {

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

        testGame.makeRectangularMap(10, 10)
        val civ = testGame.addCiv(isPlayer = true)
        gameView = GameView(testGame.gameInfo, civ)
    }

    private fun worldTileGroups(count: Int) = testGame.tileMap.values.take(count)
        .map { WorldTileGroup(gameView.tileMapView.getTile(it), tileSetStrings) }

    @Test
    fun `omitting allTiles sizes the map to just the given tileGroups, as before`() {
        val allGroups = worldTileGroups(testGame.tileMap.values.count())
        val fullMap = TileGroupMap(ZoomableScrollPane(), allGroups)

        val subsetGroups = worldTileGroups(3)
        val subsetMap = TileGroupMap(ZoomableScrollPane(), subsetGroups)

        assertTrue(
            "a map built from only 3 groups (no allTiles) should be smaller than the full map",
            subsetMap.width < fullMap.width || subsetMap.height < fullMap.height
        )
    }

    @Test
    fun `allTiles widens bounds to the full tile set even when tileGroups is a subset`() {
        val allTiles = testGame.tileMap.values.toList()
        val allGroups = allTiles.map { WorldTileGroup(gameView.tileMapView.getTile(it), tileSetStrings) }
        val fullMap = TileGroupMap(ZoomableScrollPane(), allGroups)

        val subsetGroups = worldTileGroups(3)
        val pooledMap = TileGroupMap(ZoomableScrollPane(), subsetGroups, allTiles = allTiles)

        assertEquals(fullMap.width, pooledMap.width, 0.01f)
        assertEquals(fullMap.height, pooledMap.height, 0.01f)
    }

    @Test
    fun `getTileGroupOrNull only returns groups actually passed in tileGroups`() {
        val tiles = testGame.tileMap.values.toList()
        val pooledTile = tiles[0]
        val unpooledTile = tiles[1]
        val pooledGroup = WorldTileGroup(gameView.tileMapView.getTile(pooledTile), tileSetStrings)

        val map = TileGroupMap(ZoomableScrollPane(), listOf(pooledGroup), allTiles = tiles)

        assertNotNull(map.getTileGroupOrNull(pooledTile))
        assertEquals(pooledGroup, map.getTileGroupOrNull(pooledTile))
        assertNull(map.getTileGroupOrNull(unpooledTile))
    }
}
