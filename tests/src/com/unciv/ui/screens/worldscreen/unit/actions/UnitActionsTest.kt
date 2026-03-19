package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(GdxTestRunner::class)
class UnitActionsTest {
    private var testGame = TestGame()
    private lateinit var tile: Tile
    private lateinit var civInfo: Civilization
    private lateinit var city: City

    @Before
    fun initTheWorld() {
        testGame.ruleset.units["Worker"]!!.uniques.remove("Gain [1] [Gold] <in [Desert] tiles> <once> <(modified by game speed)>")
        testGame.ruleset.units["Worker"]!!.uniques.remove("Can transform to [Warrior] <in [Desert] tiles>")
        testGame.ruleset.units["Warrior"]!!.uniques.remove("Triggers a [Tutorial Task: [Move unit]] event <once>")
        testGame.makeHexagonalMap(5)
        tile = testGame.tileMap[0,0]
        civInfo = testGame.addCiv()
        city = testGame.addCity(civInfo, tile)
        civInfo.tech.techsResearched.addAll(testGame.ruleset.technologies.keys)
        civInfo.tech.embarkedUnitsCanEnterOcean = true
        civInfo.tech.unitsCanEmbark = true
        val enemies = testGame.addCiv()
        testGame.addCity(enemies, testGame.tileMap[-4,-4])
        civInfo.getDiplomacyManagerOrMeet(enemies).declareWar()
        testGame.tileMap[-3,-3].setImprovement("Farm", enemies)
        testGame.setTileTerrain(HexCoord(-3,-4), "Desert")
    }
    
    @Test
    fun getUnitActions_worker_hasExpectedActions() {
        val unit = testGame.addUnit("Worker", civInfo, tile)
        
        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertFalse(actionList.first { it.type == UnitActionType.ConstructImprovement }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.ConnectRoad }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.GiftUnit }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Automate }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Sleep }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Explore }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Skip }.enabled())
        assertFalse(actionList.first { it.type == UnitActionType.EscortFormation }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.DisbandUnit }.enabled())
        assertEquals(9, actionList.size)
    }

    @Test
    fun getUnitActions_worker_inForeignLand_hasExpectedActions() {
        val unit = testGame.addUnit("Worker", civInfo, testGame.tileMap[-2,-2])
        unit.movement.moveToTile(testGame.tileMap[-3,-3])

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.ConstructImprovement }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.ConnectRoad }.enabled())
        assertFalse(actionList.first { it.type == UnitActionType.GiftUnit }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Automate }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Sleep }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Explore }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Skip }.enabled())
        assertFalse(actionList.first { it.type == UnitActionType.EscortFormation }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.DisbandUnit }.enabled())
        assertEquals(9, actionList.size)
    }

    @Test
    fun getUnitActions_warrior_hasExpectedActions() {
        val unit = testGame.addUnit("Warrior", civInfo, tile)

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.GiftUnit }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Automate }.enabled())
        assertFalse(actionList.first { it.currentTitle == "Upgrade to [Swordsman]\n([80] gold, [1 Iron])" }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Fortify }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Explore }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Skip }.enabled())
        assertFalse(actionList.first { it.type == UnitActionType.EscortFormation }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.DisbandUnit }.enabled())
        assertEquals(8, actionList.size)
    }

    @Test
    fun getUnitActions_warrior_inForeignLand_hasExpectedActions() {
        val unit = testGame.addUnit("Warrior", civInfo, testGame.tileMap[-2,-2])
        unit.movement.moveToTile(testGame.tileMap[-3,-3])

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertFalse(actionList.first { it.type == UnitActionType.GiftUnit }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Automate }.enabled())
        assertFalse(actionList.first { it.currentTitle == "Upgrade to [Swordsman]\n([80] gold, [1 Iron])" }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Pillage }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Fortify }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Explore }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Skip }.enabled())
        assertFalse(actionList.first { it.type == UnitActionType.EscortFormation }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.DisbandUnit }.enabled())
        assertEquals(9, actionList.size)
    }

    @Test
    fun getUnitActions_calvalry_hasExpectedActions() {
        val unit = testGame.addUnit("Horseman", civInfo, tile)

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.GiftUnit }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Automate }.enabled())
        assertFalse(actionList.first { it.currentTitle == "Upgrade to [Knight] ([100] gold)" }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Sleep }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Explore }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Skip }.enabled())
        assertFalse(actionList.first { it.type == UnitActionType.EscortFormation }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.DisbandUnit }.enabled())
        assertEquals(8, actionList.size)
    }

    @Test
    fun getUnitActions_calvalry_inForeignLand_hasExpectedActions() {
        val unit = testGame.addUnit("Horseman", civInfo, testGame.tileMap[-2,-2])
        unit.movement.moveToTile(testGame.tileMap[-3,-3])

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertFalse(actionList.first { it.type == UnitActionType.GiftUnit }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Automate }.enabled())
        assertFalse(actionList.first { it.currentTitle == "Upgrade to [Knight] ([100] gold)" }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Pillage }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Sleep }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Explore }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.Skip }.enabled())
        assertFalse(actionList.first { it.type == UnitActionType.EscortFormation }.enabled())
        assertTrue(actionList.first { it.type == UnitActionType.DisbandUnit }.enabled())
        assertEquals(9, actionList.size)
    }

    @Test
    fun getUnitActions_warrior_lowHp_canFortifyUntilHealed() {
        val unit = testGame.addUnit("Warrior", civInfo, tile)
        unit.health = 20

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.FortifyUntilHealed }.enabled())
    }

    @Test
    fun getUnitActions_calvalry_lowHp_canSleepUntiLHealed() {
        val unit = testGame.addUnit("Horseman", civInfo, tile)
        unit.health = 20

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.SleepUntilHealed }.enabled())
    }

    @Test
    fun getUnitActions_warrior_withCivilian_canEscort() {
        val warrior = testGame.addUnit("Warrior", civInfo, tile)
        testGame.addUnit("Worker", civInfo, tile)

        val actionList = UnitActions.getUnitActions(warrior, listOf(warrior)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.EscortFormation }.enabled())
    }

    @Test
    fun getUnitActions_warrior_escorting_canStopEscort() {
        val warrior = testGame.addUnit("Warrior", civInfo, tile)
        testGame.addUnit("Worker", civInfo, tile)
        warrior.startEscorting()

        val actionList = UnitActions.getUnitActions(warrior, listOf(warrior)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.StopEscortFormation }.enabled())
    }

    @Test
    fun getUnitActions_settler_canFoundCity() {
        val unit = testGame.addUnit("Settler", civInfo, testGame.tileMap[4,4])

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.FoundCity }.enabled())
    }
    
    @Test
    fun getUnitActions_withTriggerUnique_canUseIt() {
        testGame.ruleset.units["Warrior"]!!.uniques.add("Triggers a [Tutorial Task: [Move unit]] event <once>")
        val unit = testGame.addUnit("Warrior", civInfo, tile)

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.TriggerUnique }.enabled())
    }
    
    @Test
    fun getUnitActions_workBoat_canConstructImprovments() {
        for (tile in testGame.tileMap.tileList) {
            if (tile.position.x > 0) {
                tile.baseTerrain = "Coast"
                tile.setTileResource("Fish")
                tile.setTransients()
            }
        }
        
        val unit = testGame.addUnit("Work Boats", civInfo, testGame.tileMap[1,0])

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.CreateImprovement }.enabled())
    }

    @Test
    fun getUnitActions_onPillagedTerrain_canRepair() {
        val unit = testGame.addUnit("Worker", civInfo, testGame.tileMap[1,0])
        testGame.tileMap[1,0].setImprovement("Farm", civInfo, unit)
        testGame.tileMap[1,0].setPillaged()

        val actionList = UnitActions.getUnitActions(unit, listOf(unit)).toList()

        assertTrue(actionList.first { it.type == UnitActionType.Repair }.enabled())
    }

    @Test
    fun action_availableOnTile_constructImprovement_forNonCityCenter_isAvailable() {
        val unit = testGame.addUnit("Worker", civInfo, tile)

        val action = UnitActions.getUnitActions(unit, UnitActionType.ConstructImprovement, listOf(unit)).first()

        // cannot improve on city center
        assertFalse(action.availableOnTile!!(testGame.tileMap[0,0]))
        // can improve grasslands
        assertTrue(action.availableOnTile!!(testGame.tileMap[0,1]))
        assertTrue(action.availableOnTile!!(testGame.tileMap[1,0]))
        // construction *menu* can open, to show reasons we can't construct each thing
        assertTrue(action.availableOnTile!!(testGame.tileMap[2,2]))
    }

    @Test
    fun action_availableOnTile_transform_forDesert_isAvailable() {
        testGame.ruleset.units["Worker"]!!.uniques.add("Can transform to [Warrior] <in [Desert] tiles>")
        val unit = testGame.addUnit("Worker", civInfo, tile)

        val action = UnitActions.getUnitActions(unit, UnitActionType.Transform, listOf(unit)).first()

        // can transform on Desert
        assertTrue(action.availableOnTile!!(testGame.tileMap[-3, -4]))
        // cannot transform on grsslands
        assertFalse(action.availableOnTile!!(testGame.tileMap[-3,-3]))
    }

    @Test
    fun action_availableOnTile_uniqueTrigger_forDesert_isAvailable() {
        testGame.ruleset.units["Worker"]!!.uniques.add("Gain [1] [Gold] <in [Desert] tiles> <once> <(modified by game speed)>")
        val unit = testGame.addUnit("Worker", civInfo, tile)

        val action = UnitActions.getUnitActions(unit, UnitActionType.TriggerUnique, listOf(unit)).first()

        // can trigger on Desert
        assertTrue(action.availableOnTile!!(testGame.tileMap[-3, -4]))
        // cannot trigger on grsslands
        assertFalse(action.availableOnTile!!(testGame.tileMap[-3,-3]))
    }
}
