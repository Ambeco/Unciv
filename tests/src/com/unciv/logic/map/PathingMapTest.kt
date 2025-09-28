package com.unciv.logic.map

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(GdxTestRunner::class)
class PathingMapTest {

    private lateinit var tile: Tile
    private lateinit var civInfo: Civilization
    private var testGame = TestGame()

    @Before
    fun initTheWorld() {
        testGame.makeHexagonalMap(6)
        tile = testGame.tileMap[0,0]
        civInfo = testGame.addCiv()
        for (tile in testGame.tileMap.values)
            tile.setExplored(civInfo, true)
    }

    // These are interesting use-cases because it shows that for the *exact same map* for units with *no special uniques*
    //  we can still have different optimal paths!
    @Test
    fun shortestPathByTurnsNotSumOfMovements(){
        // Naive Djikstra would calculate distance to 0,3 to be 5 movement points through hills, and only 4 by going around hills.
        // However, from a *unit turn* perspective, going through the hills is 3 turns, and going around is 4, so through the hills is the correct solution
        testGame.setTileTerrain(Vector2(0f,1f), "Hill")
        testGame.setTileTerrain(Vector2(0f,2f), "Hill")
        val baseUnit = testGame.createBaseUnit()
        baseUnit.movement = 1
        val unit = testGame.addUnit(baseUnit.name, civInfo, tile)

        val pathing = PathingMap.createUnitPathingMap(unit)
        val target = testGame.getTile(Vector2(0f, 3f))
        val path = pathing.getShortestPath(target)!!

        // expect movement through hills (2 hill tiles plus one default desert)
        Assert.assertEquals(listOf(
            Vector2(0f,1f),
            Vector2(0f,2f),
            Vector2(0f,3f)),
            path.map {it.position},
        )
        Assert.assertEquals(3, pathing.getCachedNode(target)!!.turns)
        Assert.assertEquals(1f, pathing.getCachedNode(target)!!.movementUsed)
    }


    // Looks like our current movement is actually unoptimized, since it fails this test :)
    @Test
    fun getShortestPathReturnsOnlyEndOfTurns(){
        // Moving in a direct path, you'd waste 5 movement points to get there, ending up with 0.
        // Moving around the hills, you'd waste 4 movement points, leaving you with one remaining
        testGame.setTileTerrain(Vector2(0f,1f), "Hill")
        testGame.setTileTerrain(Vector2(0f,2f), "Hill")
        val baseUnit = testGame.createBaseUnit()
        baseUnit.movement = 3

        val unit = testGame.addUnit(baseUnit.name, civInfo, tile)
        val pathing = PathingMap.createUnitPathingMap(unit)
        val path = pathing.getShortestPath(testGame.getTile(Vector2(0f, 3f)))!!

        Assert.assertEquals(path.toString(), 2, path.size)
        Assert.assertEquals(path.toString(), testGame.getTile(Vector2(0f,3f)), path[1])
    }

    // These are interesting use-cases because it shows that for the *exact same map* for units with *no special uniques*
    //  we can still have different optimal paths!
    @Test
    fun shortestPathEvenWhenItsWayMoreTiles(){
        // A straight road from 0,0 up the x axis
        testGame.getTile(Vector2(0f,0f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(1f,0f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(2f,0f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(3f,0f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(4f,0f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(5f,0f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        // then straight down the y axis for 4 tiles
        testGame.getTile(Vector2(5f,1f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(5f,2f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(5f,3f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(5f,4f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        // then straight down the x axis for 4 tiles
        testGame.getTile(Vector2(4f,4f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(3f,4f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(2f,4f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(1f,4f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        testGame.getTile(Vector2(0f,4f)).setRoadStatus(RoadStatus.Railroad, civInfo)
        // The total roads are be 14 tiles, but only 1.4 movement. the direct route is 3 tiles, but
        // 3 movement.  So the road route should be chosen, despite gong way out of the way.
        val baseUnit = testGame.createBaseUnit()
        baseUnit.movement = 1
        val unit = testGame.addUnit(baseUnit.name, civInfo, tile)

        val pathing = PathingMap.createUnitPathingMap(unit)
        val target = testGame.getTile(Vector2(0f, 4f))
        val path = pathing.getShortestPath(target)!!

        // expect movement through hills (2 hill tiles plus one default desert)
        Assert.assertEquals(
            listOf(
                Vector2(4f,4f),
                Vector2(0f,4f)),
            path.map {it.position},
        )
        Assert.assertEquals(2, pathing.getCachedNode(target)!!.turns)
        Assert.assertEquals(0.4f, pathing.getCachedNode(target)!!.movementUsed, 0.05f)
    }
}
