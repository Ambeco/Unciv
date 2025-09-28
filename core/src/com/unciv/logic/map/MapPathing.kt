package com.unciv.logic.map

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.utils.Log
import yairm210.purity.annotations.Readonly

//TODO: Eventually, all path generation in the game should be moved into here.
object MapPathing {

    /**
     * We prefer the worker to prioritize paths connected by existing roads. If a tile has a road, but the civ has the ability
     * to upgrade it to a railroad, we consider it to be a railroad for pathing since it will be upgraded.
     * Otherwise, we set every tile to have equal value since building a road on any of them makes the original movement cost irrelevant.
     */
    @Suppress("UNUSED_PARAMETER") // While `from` is unused, this function should stay close to the signatures expected by the AStar and getPath `heuristic` parameter.
    @Readonly
    internal fun roadPreferredMovementCost(civ: Civilization, from: Tile, to: Tile): Float {
        // hasRoadConnection accounts for civs that treat jungle/forest as roads
        // Ignore road over river penalties.
        if ((to.hasRoadConnection(civ, false) || to.hasRailroadConnection(false)))
            return .5f

        return 1f
    }

    @Readonly
    fun isValidRoadPathTile(civ: Civilization, tile: Tile): Boolean {
        val roadImprovement = tile.ruleset.roadImprovement
        val railRoadImprovement = tile.ruleset.railroadImprovement
        
        if (tile.isWater) return false
        if (tile.isImpassible()) return false
        if (!civ.hasExplored(tile)) return false
        if (!tile.canCivPassThrough(civ)) return false
        
        return tile.hasRoadConnection(civ, false)
                || tile.hasRailroadConnection(false)
                || roadImprovement != null && tile.improvementFunctions.canBuildImprovement(roadImprovement, civ.state)
                || railRoadImprovement != null && tile.improvementFunctions.canBuildImprovement(railRoadImprovement,civ.state)
    }

    /**
     * Calculates the path for a road construction between two tiles.
     *
     * This function uses the A* search algorithm to find an optimal path for road construction between two specified tiles.
     *
     * @param civ The civlization that will construct the road.
     * @param startTile The starting tile of the path.
     * @param endTile The destination tile of the path.
     * @return A sequence of tiles representing the path from startTile to endTile, or null if no valid path is found.
     */
    @Readonly
    fun getRoadPath(civ: Civilization, startTile: Tile, endTile: Tile): List<Tile>? {
        return getPath(
            { 1f },
            1,
            { startTile },
            endTile,
            { isValidRoadPathTile(civ, it) },
            { from, to -> roadPreferredMovementCost(civ, from, to) },
            { to -> 0 },
            { it.hasConnection(civ) }
        )
    }

    /**
     * Calculates the path between two tiles.
     *
     * This function uses the A* search algorithm to find an optimal path two specified tiles on a game map.
     *
     * @param unit The unit for which the path is being calculated.
     * @param startTile The tile from which the pathfinding begins.
     * @param endTile The destination tile for the pathfinding.
     * @param predicate A function that takes a MapUnit and a Tile, returning a Boolean. This function is used to determine whether a tile can be traversed by the unit.
     * @param cost A function that calculates the cost of moving from one tile to another.
     * It takes a MapUnit, a 'from' Tile, and a 'to' Tile, returning a Float value representing the cost.
     * @return A list of tiles representing the path from the startTile to the endTile. Returns null if no valid path is found.
     */
    @Readonly
    private fun getPath(
        unit: MapUnit,
        endTile: Tile,
        predicate: (Tile) -> Boolean,
        cost: (Tile, Tile) -> Float
    ): List<Tile>? {


        return getPath(
            { unit.currentMovement },
            unit.getMaxMovement(),
            { unit.getTile() },
            endTile,
            predicate,
            cost,
            { tile -> if (unit.getDamageFromTerrain(tile) > 0) 5 else 0 },
            { it.hasConnection(unit.civ) })
    }

    /**
     * Gets the connection to the end tile. This does not take into account tile movement costs.
     * Takes in a civilization instead of a specific unit.
     */
    @Readonly
    fun getConnection(
        civ: Civilization,
        startTile: Tile,
        endTile: Tile,
        predicate: (Civilization, Tile) -> Boolean
    ): List<Tile>? {
        return getPath(
            { 1f },
            120 * 80,
            { startTile },
            endTile,
            { tile -> predicate(civ, tile) },
            { _, _ -> 1f },
            { _ -> 0 },
            { it.hasConnection(civ) })
    }

    /**
     * Calculates the path between two tiles.
     *
     * This function uses the A* search algorithm to find an optimal path two specified tiles on a game map.
     *
     * @param startTile The tile from which the pathfinding begins.
     * @param endTile The destination tile for the pathfinding.
     * @param predicate A function that takes a Tile, returning a Boolean. This function is used to determine whether a tile can be traversed.
     * @param cost A function that calculates the cost of moving from one tile to another.
     * It takes a 'from' Tile, and a 'to' Tile, returning a Float value representing the cost.
     * @return A list of tiles representing the path from the startTile to the endTile. Returns null if no valid path is found.
     */
    @Readonly
    private fun getPath(
        currentMovement: () -> Float,
        fullMovement: Int,
        startTile: () -> Tile,
        endTile: Tile,
        predicate: (Tile) -> Boolean,
        cost: (Tile, Tile) -> Float,
        turnEndPentalty: (Tile) -> Int,
        hasConnection: (Tile) -> Boolean
    ): List<Tile>? {
        val pathingMapMap = PathingMap(
            startTile,
            currentMovement,
            fullMovement,
            predicate,
            cost,
            turnEndPentalty,
            hasConnection
        )
        val result = pathingMapMap.getShortestPath(endTile)
        if (result == null)
            Log.debug("getPath failed at AStarUnitMap")
        return result
    }

}
