package com.unciv.ui.components.tilegroups

import com.badlogic.gdx.scenes.scene2d.Group
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
 * to its layer's own [com.unciv.ui.components.tilegroups.layers.TileLayer.standaloneWrapper] (see
 * [com.unciv.ui.components.tilegroups.layers.TileLayer.addOwnedActor]) - [contentActorCount] (the
 * total across all 11 of those wrappers, not [WorldTileGroup.children] itself, which is always
 * exactly 11 - one permanent wrapper `Group` per layer, regardless of how much content is inside
 * them) is a public, exact stand-in for "how many actors this group currently owns" - the thing a
 * leak would inflate.
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

    /** @see the class doc's note on why this, not [WorldTileGroup.children].size, is the real
     *  "how many actors does this group currently own" count. */
    private fun contentActorCount(group: WorldTileGroup): Int =
        group.children.sumOf { (it as Group).children.size }

    @Test
    fun `rebind matches a freshly constructed group for the same tile`() {
        val richGroup = tileGroupFor(richPos)
        richGroup.update(gameView.civView)
        check(contentActorCount(richGroup) > 0) { "test setup didn't actually exercise any layers" }

        val freshGroup = tileGroupFor(plainPos)
        freshGroup.update(gameView.civView)

        richGroup.rebind(gameView.tileMapView.getTile(testGame.getTile(plainPos)), 0f, 0f, gameView.civView)

        assertEquals(
            "rebind() left behind actors that a fresh construction of the same tile wouldn't have",
            contentActorCount(freshGroup), contentActorCount(richGroup)
        )
    }

    @Test
    fun `repeated rebind cycles don't leak actors`() {
        val group = tileGroupFor(richPos)
        group.update(gameView.civView)

        val freshGroup = tileGroupFor(plainPos)
        freshGroup.update(gameView.civView)
        val expectedPlainCount = contentActorCount(freshGroup)

        repeat(5) {
            group.rebind(gameView.tileMapView.getTile(testGame.getTile(plainPos)), 0f, 0f, gameView.civView)
            assertEquals("child count drifted on rebind cycle $it (-> plain tile)", expectedPlainCount, contentActorCount(group))

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
        check(contentActorCount(freshTwinGroup) > 0) { "test setup didn't actually exercise any layers on twinPos" }

        richGroup.rebind(gameView.tileMapView.getTile(testGame.getTile(twinPos)), 0f, 0f, gameView.civView)

        // Without clearing TileLayerResource/TileLayerImprovement's identity caches in rebind(),
        // matching resourceName/improvementPlusPillagedID would make doUpdate() think the (already
        // force-removed) old icon was still current and never recreate it - the icon would just be
        // silently missing, undercounting children versus the fresh reference group.
        assertEquals(
            "rebind() onto a tile with the same resource+improvement *names* didn't recreate their icons",
            contentActorCount(freshTwinGroup), contentActorCount(richGroup)
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

    /**
     * Regression test for a crash in [RecyclerWorldMapHolder]'s initial pool bind: that happens
     * synchronously during [WorldScreen]'s own constructor, before [com.unciv.UncivGame.Current]'s
     * `worldScreen` is set - a plain [TileGroup.rebind] onto a city tile at that point would reach
     * [com.unciv.ui.components.tilegroups.citybutton.CityButton.update]'s
     * [com.unciv.GUI.getSelectedPlayer] call and NPE (`worldScreen` still null). [rebindPositionOnly]
     * exists specifically to be safe there: it must move identity/position *without* touching any
     * layer's content at all, city button included.
     */
    @Test
    fun `rebindPositionOnly moves identity and position without updating any layer's content`() {
        val cityTile = testGame.getTile(0, 0)
        check(cityTile.isCityCenter()) { "test setup didn't actually put a city on (0,0)" }

        val group = tileGroupFor(plainPos)
        group.update(gameView.civView) // some baseline content, from a safe (non-city) tile
        check(contentActorCount(group) > 0) { "test setup didn't actually exercise any layers" }

        // Must not throw - a live WorldScreen doesn't exist in this test, so if this touched
        // CityButton.update() (via GUI.getSelectedPlayer()) it would NPE exactly like the bug did.
        group.rebindPositionOnly(gameView.tileMapView.getTile(cityTile), 78f, 90f)

        assertEquals(78f, group.x, 0.001f)
        assertEquals(90f, group.y, 0.001f)
        assertEquals(cityTile, group.tile)
        // Every layer's own rebind() unconditionally drops its owned actors from the *old* tile
        // (see e.g. TileLayerCityButton.rebind's "drops cityButtonWrapper as an owned actor"), and
        // nothing here adds any back for the new tile - only a real update() call does that -
        // so a group that's only ever had rebindPositionOnly called on it owns nothing at all.
        assertEquals(
            "rebindPositionOnly should leave every layer's content empty - only a real update() populates it",
            0, contentActorCount(group)
        )
    }
}
