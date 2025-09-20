package com.unciv.logic.map

import com.unciv.logic.map.tile.Tile
import com.unciv.utils.forEachSetBit
import com.unciv.utils.hasAnySetBit
import yairm210.purity.annotations.Cache
import yairm210.purity.annotations.InternalState
import java.util.BitSet
import java.util.PriorityQueue


data class PathNode(val tile: Tile, val parent: PathNode?, val turns: Int, val movementRemaining: Float)
data class PrioritizedNode(val pathNode: PathNode, val priority: Float)

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
 * @param maxMoveBonus The maximum movement cost multiplier for the heuristic function. If the
 *                    fastest movement multiplier is railroads at 0.25x, this should be 0.25f.
 * @param predicate A function that determines if a tile should be considered for further exploration.
 *                  For instance, it might return `true` for passable tiles and `false` for obstacles.
 * @param cost A function that takes two tiles (fromTile, toTile) as input and returns the cost
 *                     of moving from 'fromTile' to 'toTile' as a Float. This allows for flexible cost
 *                     calculations based on different criteria, such as distance, terrain, or other
 *                     custom logic defined by the user.
 */
@InternalState
class PathingMap(
    private val startingPoint: Tile,
    private val currentMovement: Float,
    private val fullMovement: Int,
    private val predicate : (Tile) -> Boolean,
    private val cost: (Tile, Tile) -> Float,
    private val maxMoveBonus : Float = 0.1f) {

    /**
     * Frontier list of the tiles to be checked.
     * 
     * In exceptional cases, a node already calculated may be left here, and recalculated again
     * later.
     *
     * Bitset used to minimize memory allocations
     */
    @Cache
    private val tilesToCheck = BitSet(startingPoint.tileMap.tileList.size)
    
    /**
     * A BitSet to track which tiles have already been checked.
     * This helps avoid redundant calculations and ensures each tile is processed only once.
     * 
     * Bitset used to minimize memory allocations
     */
    @Cache
    private val tilesChecked = BitSet(startingPoint.tileMap.tileList.size)

    /**
     * A map where each tile reached during the search points to its parent tile.
     * This map is used to reconstruct the path once the destination is reached.
     * 
     * Theoretically, this can be replaced with three separate arrays for each field, eliminiating
     * the separate allocations per-node, but it's unclear if the performance is worth the
     * complexity.
     */
    @Cache
    private val tilesReached = Array<PathNode?>(startingPoint.tileMap.tileList.size, {null})


    init {
        val root = PathNode(startingPoint, null, 0, currentMovement)
        tilesReached[startingPoint.zeroBasedIndex] = root
        tilesToCheck[startingPoint.zeroBasedIndex] = true
    }

    /**
     * Use a AStarPathfinder instance to calculate the route, with thread-safe way
     **/
    private fun stepUntilDestination(destination: Tile, maxTilesToAnalyze: Int = Int.MAX_VALUE) {
        // tilesReached can have extra data, so pathfinders can all write to it directly, safely
        // but tilesChecked and tilesToCheck need to be a coherent snapshot, so we use a lock
        val tilesChecked: BitSet
        val tilesToCheck: BitSet
        synchronized(this.tilesChecked) {
            tilesChecked = this.tilesChecked.clone() as BitSet
            tilesToCheck = this.tilesToCheck.clone() as BitSet
        }
        
        // now create the calculator and let it do its work
        val finder = AStarPathfinder(
                startingPoint,
                fullMovement,
                predicate,
                cost,
                maxMoveBonus,
                tilesReached,
                tilesChecked,
                tilesToCheck
            )
        finder.stepUntilDestination(destination, maxTilesToAnalyze)
        
        // now merge the pathfinder's tilesChecked and tilesToCheck back into the shared PathingData
        // again using a synchronized block not just for thread-safety, but also to ensure atomicity
        synchronized(this.tilesChecked) {
            // for each tile that we still needed to check, mark it in the shared state as needing
            // to be checked.... as long as nobody else already checked it.
            finder.todo.map { it.pathNode.tile.zeroBasedIndex }
                .map { tilesToCheck[it] = !tilesChecked[it] }
            // For each tile we already checked, mark them as checked, and not needing to be checked
            tilesChecked.or(finder.tilesChecked)
            tilesToCheck.andNot(finder.tilesChecked)
        }
    }

    /**
     * Constructs a sequence representing the path from the given destination tile back to the starting point.
     * If the destination has not been reached, the sequence will be empty.
     *
     * @param destination The destination tile to trace the path to.
     * @return A sequence of tiles representing the path from the destination to the starting point.
     */
    fun getPathTo(destination: Tile, maxTilesToAnalyze: Int = Int.MAX_VALUE): List<Tile>? {
        if (tilesReached[destination.zeroBasedIndex] == null && tilesToCheck.hasAnySetBit()) {
            stepUntilDestination(destination, maxTilesToAnalyze)
        }
        // Now tilesReached has the shortest route, so we extract it into a list and return 
        var currentNode: PathNode = tilesReached[destination.zeroBasedIndex] ?: return null
        val result = mutableListOf<Tile>()
        while(true) {
            val parent = currentNode.parent
            result.add(currentNode.tile)
            if (parent == null) break
            currentNode = parent
        }
        return result.asReversed()
    }
}

internal class AStarPathfinder(
    startingPoint: Tile,
    private val fullMovement: Int,
    private val predicate : (Tile) -> Boolean,
    private val cost: (Tile, Tile) -> Float,
    private val maxMoveBonus : Float = 0.1f,
    private val tilesReached: Array<PathNode?>,
    internal val tilesChecked: BitSet,
    initialTilesToCheck: BitSet
    ) {
    
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
        startingPoint.tileMap.tileMatrix.size * 2 +
            startingPoint.tileMap.tileMatrix[0].size * 2,
        { tp1, tp2 -> tp1.priority.compareTo(tp2.priority) })
    
    /*
     * Separate init function so that this work can occur outside 
     */
    init {
        // Add all the initial tiles to check to the priority queue
        initialTilesToCheck.forEachSetBit { 
            todo.add(asPrioritizedNode(tilesReached[it]!!, startingPoint))
        }
    }

    // lower values are higher priority
    private fun asPrioritizedNode(node: PathNode, destination: Tile): PrioritizedNode {
        val movementSoFar = node.turns * fullMovement + node.movementRemaining
        val underestimatedMoveRemaining = node.tile.aerialDistanceTo(destination) * maxMoveBonus
        // TODO: estimate potential roads and impossible roads separately
        return PrioritizedNode(node, movementSoFar + underestimatedMoveRemaining)
    }

    private fun createNextNode(currentNode: PathNode, neighborTile: Tile): PathNode {
        val cost = cost(currentNode.tile, neighborTile)
        return if (cost > currentNode.movementRemaining)
            PathNode(neighborTile, currentNode, currentNode.turns + 1, fullMovement - cost)
        else
            PathNode(neighborTile, currentNode, currentNode.turns, currentNode.movementRemaining - cost)
    }
    
    internal fun stepUntilDestination(destination: Tile, maxTilesToAnalyze: Int) {
        var remainingTime = maxTilesToAnalyze
        while (tilesReached[destination.zeroBasedIndex] == null && remainingTime-- > 0) {
            val currentNode = todo.poll() ?: return
            for (neighborTile in currentNode.pathNode.tile.neighbors) {
                var neighborNode = tilesReached[neighborTile.zeroBasedIndex]
                // If no other thread has calculated this neighbor yet, then calculate it
                if (neighborNode == null && predicate(neighborTile)) {
                    neighborNode = createNextNode(currentNode.pathNode, neighborTile)
                    tilesReached[neighborTile.zeroBasedIndex] = neighborNode
                }
                // add this neighbor to the todo list.  We have to do this even if another thread
                // calculated it, because we need to check it's neighbors recursively
                if (neighborNode != null) {
                    todo.add(asPrioritizedNode(neighborNode, destination))
                }
            }
            tilesChecked.set(currentNode.pathNode.tile.zeroBasedIndex)
        }
    }
}
