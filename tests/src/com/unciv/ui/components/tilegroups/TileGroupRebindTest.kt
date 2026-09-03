package com.unciv.ui.components.tilegroups

import com.unciv.dev.FontDesktop
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TileGroup.rebind] lets a fixed [TileGroup] be repointed at a different [com.unciv.logic.map.tile.Tile]
 * without reconstructing it or any of its 11 [com.unciv.ui.components.tilegroups.layers.TileLayer]s -
 * the building block a pooled/recycling map holder needs to avoid keeping one [TileGroup] alive per
 * map tile. [com.unciv.ui.components.tilegroups.layers.TileLayer.update] alone isn't safe for this:
 * it's written to incrementally diff against the *same* tile's previous state, so several layers
 * have caches or "already correctly positioned" actors keyed by the *old* tile that [rebind] has to
 * explicitly clear (see the individual [com.unciv.ui.components.tilegroups.layers.TileLayer.rebind]
 * overrides for what and why).
 *
 * Since most of that owned-actor bookkeeping is `internal` (invisible from the separate `tests`
 * module), these tests work structurally: a [WorldTileGroup] not attached to any
 * [com.unciv.ui.components.tilegroups.layers.TileMapLayer] falls back to adding every owned actor
 * as a direct scene2d child of the group itself (see [com.unciv.ui.components.tilegroups.layers.TileLayer.addOwnedActor]),
 * so `children.size` is a public, exact stand-in for "how many actors this group currently owns" -
 * the thing a leak would inflate.
 */
@RunWith(GdxTestRunner::class)
class TileGroupRebindTest {

    private lateinit var testGame: TestGame
    private lateinit var tileSetStrings: TileSetStrings
    private lateinit var gameView: GameView

    /** An owned, non-city tile with a road, a resource+improvement, and a unit on it - so a group
     *  bound here accumulates state in as many of the 11 layers as practical. Deliberately *not*
     *  the city center tile itself: TileLayerCityButton's doUpdate() reaches into GUI.getSelectedPlayer(),
     *  which needs a live WorldScreen this test has no reason to construct. */
    private lateinit var richPos: HexCoord
    private lateinit var plainPos: HexCoord
    /** Same resource+improvement *names* as [richPos] (but otherwise unrelated/distant) - exercises
     *  the layers whose doUpdate() only recreates an icon when its identifier actually *changed*
     *  (see TileLayerResource/TileLayerImprovement.rebind's doc): with matching names, that
     *  diff-check alone can't tell "still the same icon" apart from "coincidentally same name on a
     *  new tile", so it's rebind()'s job to force the recreation instead. */
    private lateinit var twinPos: HexCoord

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

        val resourceName = testGame.ruleset.tileResources.values.first().name
        val improvement = testGame.createTileImprovement()

        val cityTile = testGame.getTile(0, 0)
        val city = testGame.addCity(civ, cityTile)
        val richTile = cityTile.neighbors.first()
        testGame.addTileToCity(city, richTile)
        cityTile.roadStatus = RoadStatus.Road
        richTile.roadStatus = RoadStatus.Road
        richTile.setTileResource(resourceName)
        richTile.setImprovementBasic(improvement.name)
        testGame.addUnit("Warrior", civ, richTile)
        richPos = richTile.position

        // Tiles ordered farthest-from-the-city-first, so the ones we pick below are as unrelated
        // to richTile (city-adjacent) as this small a map allows - exact distance doesn't matter.
        val farTiles = testGame.tileMap.values
            .filter { it.position != cityTile.position && it.position != richTile.position }
            .sortedByDescending { it.aerialDistanceTo(cityTile) }

        val twinTile = farTiles[0]
        twinTile.setTileResource(resourceName)
        twinTile.setImprovementBasic(improvement.name)
        twinPos = twinTile.position

        plainPos = farTiles.first { it.position != twinTile.position }.position
    }

    private fun tileGroupFor(position: HexCoord): WorldTileGroup {
        val tile = testGame.getTile(position)
        val group = WorldTileGroup(gameView.tileMapView.getTile(tile), tileSetStrings)
        group.isForceVisible = true
        return group
    }

    @Test
    fun `rebind matches a freshly constructed group for the same tile`() {
        val richGroup = tileGroupFor(richPos)
        richGroup.update(gameView.civView)
        check(richGroup.children.size > 0) { "test setup didn't actually exercise any layers" }

        val freshGroup = tileGroupFor(plainPos)
        freshGroup.update(gameView.civView)

        richGroup.rebind(gameView.tileMapView.getTile(testGame.getTile(plainPos)), 0f, 0f, gameView.civView)

        assertEquals(
            "rebind() left behind actors that a fresh construction of the same tile wouldn't have",
            freshGroup.children.size, richGroup.children.size
        )
    }

    @Test
    fun `repeated rebind cycles don't leak actors`() {
        val group = tileGroupFor(richPos)
        group.update(gameView.civView)

        val freshGroup = tileGroupFor(plainPos)
        freshGroup.update(gameView.civView)
        val expectedPlainCount = freshGroup.children.size

        repeat(5) {
            group.rebind(gameView.tileMapView.getTile(testGame.getTile(plainPos)), 0f, 0f, gameView.civView)
            assertEquals("child count drifted on rebind cycle $it (-> plain tile)", expectedPlainCount, group.children.size)

            group.rebind(gameView.tileMapView.getTile(testGame.getTile(richPos)), 0f, 0f, gameView.civView)
            // Just needs to be stable across cycles, not equal to any particular fresh baseline
            // (rich tile isn't force-deterministic across repeated updates the way an empty tile is).
        }
    }

    @Test
    fun `rebind recreates icons even when the new tile has identical resource+improvement names`() {
        val richGroup = tileGroupFor(richPos)
        richGroup.update(gameView.civView)

        val freshTwinGroup = tileGroupFor(twinPos)
        freshTwinGroup.update(gameView.civView)
        check(freshTwinGroup.children.size > 0) { "test setup didn't actually exercise any layers on twinPos" }

        richGroup.rebind(gameView.tileMapView.getTile(testGame.getTile(twinPos)), 0f, 0f, gameView.civView)

        // Without clearing TileLayerResource/TileLayerImprovement's identity caches in rebind(),
        // matching resourceName/improvementPlusPillagedID would make doUpdate() think the (already
        // force-removed) old icon was still current and never recreate it - the icon would just be
        // silently missing, undercounting children versus the fresh reference group.
        assertEquals(
            "rebind() onto a tile with the same resource+improvement *names* didn't recreate their icons",
            freshTwinGroup.children.size, richGroup.children.size
        )
    }

    @Test
    fun `rebind moves layers to the new tile's position`() {
        val group = tileGroupFor(richPos)
        group.update(gameView.civView)

        group.rebind(gameView.tileMapView.getTile(testGame.getTile(plainPos)), 123f, 456f, gameView.civView)

        assertEquals(123f, group.x, 0.001f)
        assertEquals(456f, group.y, 0.001f)
        assertEquals(testGame.getTile(plainPos), group.tile)
    }
}
