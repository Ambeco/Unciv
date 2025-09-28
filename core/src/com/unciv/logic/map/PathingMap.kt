package com.unciv.logic.map

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.MapPathing.roadPreferredMovementCost
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.MovementCost
import com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.mapunit.movement.UnitMovement.ParentTileAndTotalMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.utils.forEachSetBit
import com.unciv.utils.hasAnySetBit
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.InternalState
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import java.util.BitSet
import java.util.PriorityQueue

data class PrioritizedNode(val priority: Float, val pathNode: ParentTileAndTotalMovement)

/**
 * AStar is an implementation of the A* search algorithm, commonly used for finding the shortest path
 *  * in a weighted graph.
 *
 * A* uses dynamic programming, which means the temporary results of subproblems can be cached
 * and reused for other Pathing problems for the same unit, as long as its current movement has not
 * changed, nor any of the predicates or cost functions.
 *
 * This is designed so that multiple A* searches can calculate using the same PathingData instance
 * in parallel from multiple threads, though separate threads in parallel will often duplicate work
 *
 * @param startingPoint The initial tile where the search begins.
 * @param currentMovement The movement points available for the unit at the start of the search.
 * @param fullMovement The movement points available for the unit at the start of each turn.
 * @param predicate A function that determines if a tile should be considered for further exploration.
 *                  For instance, it might return `true` for passable tiles and `false` for obstacles.
 * @param cost A function that takes two tiles (fromTile, toTile) as input and returns the cost
 *                     of moving from 'fromTile' to 'toTile' as a Float. This allows for flexible cost
 *                     calculations based on different criteria, such as distance, terrain, or other
 *                     custom logic defined by the user.
 */
@InternalState
class PathingMap(
    private val lazyStartingPoint: () -> Tile,
    private val lazyCurrentMovement: () -> Float,
    private val fullMovement: Int,
    private val predicate: (Tile) -> Boolean,
    private val cost: (Tile, Tile) -> Float,
    private val turnEndPenalty: (Tile) -> Int,
    private val hasConnection: (Tile) -> Boolean
) {
    // Cache these separately, so we can automatically reset the caches if they change
    internal var startingPoint = lazyStartingPoint()
    internal var currentMovement = lazyCurrentMovement()

    /**
     * Frontier list of the tiles to be checked.
     *
     * In exceptional cases, a node already calculated may be left here, and recalculated again
     * later.
     *
     * Bitset used to minimize memory allocations
     */
    @Cache
    private val tilesToCheck = BitSet(UncivGame.Current.gameInfo!!.tileMap.tileList.size)

    /**
     * A BitSet to track which tiles have already been checked.
     * This helps avoid redundant calculations and ensures each tile is processed only once.
     *
     * Bitset used to minimize memory allocations
     */
    @Cache
    private val tilesChecked = BitSet(UncivGame.Current.gameInfo!!.tileMap.tileList.size)

    /**
     * A map where each tile reached during the search points to its parent tile.
     * This map is used to reconstruct the path once the destination is reached.
     *
     * Theoretically, this can be replaced with three separate arrays for each field, eliminiating
     * the separate allocations per-node, but it's unclear if the performance is worth the
     * complexity.
     */
    @Cache
    private val tilesReached = Array<ParentTileAndTotalMovement?>(UncivGame.Current.gameInfo!!.tileMap.tileList.size) { null }

    @Cache
    private lateinit var tilesSameTurn: PathsToTilesWithinTurn


    init {
        clear()
    }

    /**
     * This is the only method that is NOT thread-safe.
     */
    fun clear() {
        startingPoint = lazyStartingPoint()
        currentMovement = lazyCurrentMovement()
        tilesToCheck.clear()
        tilesChecked.clear()
        tilesReached.fill(null)
        val root = ParentTileAndTotalMovement(startingPoint, startingPoint, 0, currentMovement)
        tilesReached[startingPoint.zeroBasedIndex] = root
        tilesToCheck[startingPoint.zeroBasedIndex] = true
        tilesSameTurn = PathsToTilesWithinTurn()
    }

    /**
     * Constructs a sequence representing the path from the given destination tile back to the starting point.
     * If the destination has not been reached, the sequence will be empty.
     *
     * @param destination The destination tile to trace the path to.
     * @return A sequence of tiles representing the path from the destination to the starting point.
     */
    fun getShortestPath(destination: Tile, maxTurns: Int = DEFAULT_TIMEOUT): List<Tile>? {
        if (startingPoint != lazyStartingPoint() || currentMovement != lazyCurrentMovement()) {
            clear()
        }
        if (tilesReached[destination.zeroBasedIndex] == null && tilesToCheck.hasAnySetBit()) {
            stepUntilDestination(destination, maxTurns)
        }
        // Now tilesReached has the shortest route, so we extract it into a list and return 
        var currentNode: ParentTileAndTotalMovement = tilesReached[destination.zeroBasedIndex] ?: return null
        val result = mutableListOf(currentNode.tile)
        while (true) {
            val parent = tilesReached[currentNode.parentTile.zeroBasedIndex]!!
            if (parent.tile.zeroBasedIndex == startingPoint.zeroBasedIndex) break
            if (parent.turns < currentNode.turns)
                result.add(parent.tile)
            currentNode = parent
        }
        return result.asReversed()
    }


    /**
     * Gets the tiles the unit could move to with remaining movement this turn.
     * Does not consider if tiles can actually be entered, use canMoveTo for that.
     * If a tile can be reached within the turn, but it cannot be passed through, the total distance to it is set to unitMovement
     */
    fun getMovementToTilesAtPosition(): PathsToTilesWithinTurn  {
        if (startingPoint != lazyStartingPoint() || currentMovement != lazyCurrentMovement()) {
            clear()
        }
        val localTilesSameTurn = tilesSameTurn
        if (localTilesSameTurn.isNotEmpty()) return localTilesSameTurn
        if (tilesToCheck.hasAnySetBit()) {
            stepUntilDestination(null, 1)
        }
        val newTilesSameTurn = PathsToTilesWithinTurn()
        tilesChecked.forEachSetBit {
            if (tilesReached[it]?.turns == 1)
                newTilesSameTurn.put(tilesReached[it]!!.tile, tilesReached[it]!!)
        }
        tilesSameTurn = newTilesSameTurn
        return newTilesSameTurn
    }

    @VisibleForTesting
    @Readonly
    fun getCachedNode(tile: Tile): ParentTileAndTotalMovement? {
        return tilesReached[tile.zeroBasedIndex]
    }

    /**
     * Use a AStarPathfinder instance to calculate the route, with thread-safe way
     **/
    private fun stepUntilDestination(destination: Tile?, maxTurns: Int) {
        // tilesReached can have extra data, so pathfinders can all write to it directly, safely
        // but tilesChecked and tilesToCheck need to be a coherent snapshot, so we use a lock
        val copiedTilesChecked: BitSet
        val copiedTilesToCheck: BitSet
        synchronized(this.tilesChecked) {
            copiedTilesChecked = this.tilesChecked.clone() as BitSet
            copiedTilesToCheck = this.tilesToCheck.clone() as BitSet
        }

        // now create the calculator and let it do its work
        val finder = AStarPathfinder(
            startingPoint,
            destination,
            fullMovement,
            predicate,
            cost,
            turnEndPenalty,
            hasConnection,
            tilesReached,
            maxTurns,
            copiedTilesChecked,
            copiedTilesToCheck
        )
        finder.stepUntilDestination()

        // now merge the pathfinder's tilesChecked and tilesToCheck back into the shared PathingData
        // again using a synchronized block not just for thread-safety, but also to ensure atomicity
        synchronized(this.tilesChecked) {
            // for each tile that we still needed to check, remove the ones checked by other threads
            // and then add them to the global queue
            finder.tilesInTodo.andNot(tilesChecked)
            tilesToCheck.or(finder.tilesInTodo)
            // For each tile we already checked, mark them as checked, and not needing to be checked
            tilesChecked.or(finder.tilesChecked)
            tilesToCheck.andNot(finder.tilesChecked)
        }
    }

    companion object {

        @Readonly
        fun createUnitPathingMap(unit: MapUnit): PathingMap {
            return PathingMap(
                { unit.getTile() },
                {
                    unit.currentMovement.coerceAtMost(
                        unit.getOtherEscortUnit()?.currentMovement ?: (128 * 80F)
                    )
                },
                unit.getMaxMovement().coerceAtMost(
                    unit.getOtherEscortUnit()?.getMaxMovement() ?: (128 * 80)
                ),
                { unit.movement.canPassThrough(it) },
                { from, to ->
                    MovementCost.getMovementCostBetweenAdjacentTilesEscort(
                        unit,
                        from,
                        to
                    )
                },
                { to ->
                    if (unit.getDamageFromTerrain(to) > 0) AStarPathfinder.DAMAGING_TERRAIN_TURN_PENALTY
                    else 0
                },
                { it.hasConnection(unit.civ) }
            )
        }

        @Readonly
        fun createRoadPathingMap(civ: Civilization, startingPoint: Tile): PathingMap {
            return PathingMap(
                { startingPoint },
                { DEFAULT_TIMEOUT.toFloat() },
                DEFAULT_TIMEOUT ,
                { MapPathing.isValidRoadPathTile(civ, it) },
                { from, to -> roadPreferredMovementCost(civ, from, to) },
                { to -> 0 },
                { it.hasConnection(civ) }
            )
        }

        // by default, analyze the whole map
        internal const val DEFAULT_TIMEOUT: Int = 128 * 80
    }
}

@InternalState
internal class AStarPathfinder(
    startingPoint: Tile,
    private val destination: Tile?,
    private val fullMovement: Int,
    private val predicate: (Tile) -> Boolean,
    private val cost: (Tile, Tile) -> Float,
    private val turnEndPenalty: (Tile) -> Int,
    private val hasConnection: (Tile) -> Boolean,
    private val tilesReached: Array<ParentTileAndTotalMovement?>,
    private var maxTurns: Int,
    internal val tilesChecked: BitSet,
    initialTilesToCheck: BitSet
) {
    internal val tilesInTodo: BitSet = BitSet(tilesReached.size)

    /**
     * Frontier priority queue for managing the tiles to be checked.
     * Tiles are ordered based on their priority, determined by the cumulative cost so far and the
     * heuristic estimate to the goal.
     *
     * Theoretically, this could be replaced with two separate arrays. One LongArray holding both
     * the parent index (high bits) and the priority (low bits), and one FloatArray holding the
     * priority for sorting. Theoretically, if the movement cost was a FixedPoint number instead
     * of a Float, then the entire node could stored in a Long, and the queue replaced with a
     * LongArray.This would eliminate the per-node allocation, but it's unclear if the performance
     * is worth the complexity.
     */
    internal val todo = PriorityQueue<PrioritizedNode>(
        UncivGame.Current.gameInfo!!.tileMap.tileMatrix.size * 2 +
            UncivGame.Current.gameInfo!!.tileMap.tileMatrix[0].size * 2
    ) { tp1, tp2 -> tp1.priority.compareTo(tp2.priority) }

    /*
     * Separate init function so that this work can occur outside 
     */
    init {
        // Add all the initial tiles to check to the priority queue
        initialTilesToCheck.forEachSetBit {
            val tile = tilesReached[it]
            if (tile != null && tile.turns <= maxTurns) {
                todo.add(asPrioritizedNode(tile))
                tilesInTodo.set(it)
            }
        }
    }

    // Heuristics for not-yet-calculated tiles here based on distance to target        
    // lower values are higher priority
    @Readonly
    private fun asPrioritizedNode(node: ParentTileAndTotalMovement): PrioritizedNode {
        val movementSoFar = (node.turns - 1) * fullMovement + node.movementUsed
        val minRemainingTiles = destination?.let {node.tile.aerialDistanceTo(it) } ?: 0
        val minRemainingCost = if (hasConnection(node.tile))
            minRemainingTiles * FASTEST_ROAD_COST
        else
            (minRemainingTiles - 1) * (FASTEST_NON_ROAD_COST) + FASTEST_NON_ROAD_COST
        return PrioritizedNode(movementSoFar + minRemainingCost, node)
    }

    @Pure
    private fun createNextNode(currentNode: ParentTileAndTotalMovement, neighborTile: Tile): ParentTileAndTotalMovement {
        val cost = cost(currentNode.tile, neighborTile)
        // Note that if we can't move there this turn, then we unconditionally move there next turn.
        // In some cases, this can use more than the remaining movement, but that's correct behavior.
        // https://yairm210.medium.com/multi-turn-pathfinding-7136bd0bdaf0
        if (currentNode.movementUsed + cost <= fullMovement || currentNode.movementUsed == 0f)
            return ParentTileAndTotalMovement(
                neighborTile, currentNode.tile, currentNode.turns,
                currentNode.movementUsed + cost
            )
        val damagingTerrainPenalty = turnEndPenalty(neighborTile)
        return ParentTileAndTotalMovement(
            neighborTile, currentNode.tile, currentNode.turns + 1 + damagingTerrainPenalty,
            cost
        )
    }

    internal fun stepUntilDestination() {
        while (true) {
            val currentNode = todo.poll() ?: return
            tilesInTodo.clear(currentNode.pathNode.tile.zeroBasedIndex)
            tilesChecked.set(currentNode.pathNode.tile.zeroBasedIndex)

            for (neighborTile in currentNode.pathNode.tile.neighbors) {
                // if it's already checked, or queued, then ignore this neighbor
                if (tilesChecked[neighborTile.zeroBasedIndex] || tilesInTodo[neighborTile.zeroBasedIndex]) continue
                var neighborNode = tilesReached[neighborTile.zeroBasedIndex]
                if (!predicate(neighborTile)) {
                    //mark it as already calculated, so we skip it in future
                    tilesChecked[neighborTile.zeroBasedIndex] = true
                    continue
                }
                // If no other thread has calculated this neighbor yet, then calculate it
                if (neighborNode == null) {
                    neighborNode = createNextNode(currentNode.pathNode, neighborTile)
                    tilesReached[neighborTile.zeroBasedIndex] = neighborNode
                }
                // add this neighbor to the todo list.  We have to do this even if another thread
                // calculated it, because we need to add its neighbors to our local todo list
                if (neighborNode.turns <= maxTurns)
                    todo.add(asPrioritizedNode(neighborNode))
                tilesInTodo.set(neighborTile.zeroBasedIndex)
            }

            // if we reached the destination, (or if another thread did), then we stop
            if (destination != null && tilesReached[destination.zeroBasedIndex] != null) return
        }
    }

    companion object {
        const val FASTEST_ROAD_COST = 0.1f
        const val FASTEST_NON_ROAD_COST = 1f
        const val DAMAGING_TERRAIN_TURN_PENALTY = 5
    }
}
