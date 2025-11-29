package com.unciv.logic.map.mapunit.movement

import com.unciv.UncivGame
import com.unciv.logic.map.FixedPointMovement
import com.unciv.logic.map.FixedPointMovement.Companion.fpmFromFixedPointBits
import com.unciv.logic.map.FixedPointMovement.Companion.fpmFromMovement
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.utils.ImmutableIntIntArrayMap
import com.unciv.utils.ImmutableIntIntArrayMap.Companion.AbstractBuilder
import com.unciv.utils.ImmutableIntIntArrayMap.Companion.Entry
import com.unciv.utils.Log
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly

// map of tileZeroBasedIndex to ParentTileAndTotalMovement
interface PathsToTilesWithinTurn {
    @Readonly fun getPathToTile(tile: Tile): List<Tile>

    @Readonly fun isEmpty(): Boolean
    @Readonly fun isNotEmpty(): Boolean
    val size: Int
    @Readonly operator fun contains(tile: Tile): Boolean
    @Readonly fun containsKey(tile: Tile): Boolean
    @Readonly fun getMovement(tile: Tile): Float
    @Readonly fun getParentTile(tile: Tile): Tile
    // no operator fun get(tile:Tile) because of a compiler bug that causes JVM to crash when PathsToTilesWithinTurnMap is loaded

    @Readonly fun forEachTile(op: (Tile)->Unit)
    @Readonly fun filter(predicate: (Tile) -> Boolean): PathsToTilesWithinTurn
    @Readonly fun asTileSequence(): Sequence<Tile>
    @Readonly fun tilesSortedBy(selector: (Tile) -> Float): Sequence<Tile>
    @Readonly fun tilesSortedByDescending(selector: (Tile) -> Float) = tilesSortedBy {tile -> -selector(tile) }

    @Readonly fun any(): Boolean
    @Readonly fun anyTile(predicate: (Tile)->Boolean): Boolean
    @Readonly fun <R>firstNotNullTileOfOrNull(mapping: (Tile) -> R?): R?
    @Readonly fun <R> firstNotNullTileOf(mapping: (Tile) -> R?) = firstNotNullTileOfOrNull(mapping)!!
    @Readonly fun minTileByOrNull(selector: (Tile) -> Float): Tile?
    @Readonly fun minTileBy(selector: (Tile) -> Float) = minTileByOrNull(selector)!!
    @Readonly fun maxTileByOrNull(selector: (Tile) -> Float) = minTileByOrNull {tile -> -selector(tile) }
    @Readonly fun maxTileBy(selector: (Tile) -> Float) = maxTileByOrNull(selector)!!
    fun randomTile(): Tile
    
    interface Builder {
        fun reserve(minCapacity: Int): Builder
        fun set(tile: Tile, parentTile: Tile, totalMovement: Float): Builder
        operator fun set(tile: Tile, value: ParentTileAndTotalMovementValue): Builder
        fun build(): PathsToTilesWithinTurn
    }
    
    companion object {
        @Readonly fun newBuilder(tileMap: TileMap, initialCapacity: Int = 32): Builder {
            return if (UncivGame.Current.settings.useAStarPathfinding) PathsToTilesWithinTurnArrayMap.Builder(tileMap, null, initialCapacity)
            else PathsToTilesWithinTurnHashMap.Builder(initialCapacity)
        }
        @Readonly fun of(tile: Tile, parentTile: Tile, totalMovement: Float): PathsToTilesWithinTurn {
            @LocalState
            val builder = newBuilder(tile.tileMap, 1)
            builder.set(tile, parentTile, totalMovement)
            return builder.build()
        }
    }
}

interface ParentTileAndTotalMovement {
    val totalMovement: Float
    @Readonly fun parentTile(tileMap: TileMap): Tile
}

class PathsToTilesWithinTurnHashMap(initialCapacity: Int = 16): LinkedHashMap<Tile, ParentTileAndTotalMovement>(initialCapacity), PathsToTilesWithinTurn {
    override fun getPathToTile(tile: Tile): List<Tile> {
        if (!containsKey(tile)) {
            Log.debug("PathsToTilesWithinTurn#getPathToTile does not contain $tile: $this")
            throw Exception("Can't reach $tile")
        }
        val tileMap = tile.tileMap
        val reversePathList = ArrayList<Tile>()
        var currentTile = tile
        while (get(currentTile)!!.parentTile(tileMap) != currentTile) {
            reversePathList.add(currentTile)
            currentTile = get(currentTile)!!.parentTile(tileMap)
        }
        return reversePathList.reversed()
    }
    override fun any(): Boolean = isNotEmpty()
    override fun isNotEmpty(): Boolean = size > 0
    override fun contains(tile: Tile): Boolean
        = (this as LinkedHashMap<Tile, ParentTileAndTotalMovement>).contains(tile)

    override fun getMovement(tile: Tile): Float = super.get(tile)!!.totalMovement
    override fun getParentTile(tile: Tile): Tile = super.get(tile)!!.parentTile(tile.tileMap)

    override fun forEachTile(op: (Tile)->Unit): Unit = super.forEach {k,_-> op(k) }

    override fun filter(predicate: (Tile) -> Boolean): PathsToTilesWithinTurn {
        @LocalState
        val r = PathsToTilesWithinTurnHashMap()
        r.putAll((this as LinkedHashMap<Tile, ParentTileAndTotalMovement>).filter{predicate(it.key)})
        return r
    }

    override fun asTileSequence(): Sequence<Tile> = keys.asSequence()

    override fun tilesSortedBy(selector: (Tile) -> Float): Sequence<Tile>
        = (this as LinkedHashMap<Tile, ParentTileAndTotalMovement>).asSequence().sortedBy{selector(it.key)}.map { it.key }

    override fun anyTile(predicate: (Tile) -> Boolean): Boolean
        = (this as LinkedHashMap<Tile, ParentTileAndTotalMovement>).any {predicate(it.key)}

    override fun <R>firstNotNullTileOfOrNull(mapping: (Tile) -> R?): R?
        = (this as LinkedHashMap<Tile, ParentTileAndTotalMovement>).firstNotNullOfOrNull {mapping(it.key)}

    override fun minTileByOrNull(selector: (Tile) -> Float): Tile?
        = (this as LinkedHashMap<Tile, ParentTileAndTotalMovement>).minByOrNull{selector(it.key)}?.key

    override fun randomTile(): Tile
        = (this as LinkedHashMap<Tile, ParentTileAndTotalMovement>).keys.random()

    class Builder(initialCapacity: Int = 16) : PathsToTilesWithinTurn.Builder {
        private val map = PathsToTilesWithinTurnHashMap(initialCapacity + initialCapacity/4)
        override fun reserve(minCapacity: Int): PathsToTilesWithinTurn.Builder = this
        override fun set(tile: Tile, parentTile: Tile, totalMovement: Float): Builder {
            map[tile] = ParentTileAndTotalMovementData(parentTile, totalMovement)
            return this
        }
        override fun set(tile: Tile, value: ParentTileAndTotalMovementValue): Builder {
            map[tile] = value
            return this
        }

        override fun build(): PathsToTilesWithinTurn = map
    }
}

data class ParentTileAndTotalMovementData(
    val parentTile: Tile,
    override val totalMovement: Float
) :ParentTileAndTotalMovement {
    @Pure override fun parentTile(tileMap: TileMap) = parentTile
}

class PathsToTilesWithinTurnArrayMap(
    map: LongArray, size: Int, val tileMap: TileMap, private val unit: MapUnit?=null)
    : ImmutableIntIntArrayMap(map, size), PathsToTilesWithinTurn
{
    @Readonly fun Entry.getEntryTile() = tileMap.tileList[key]

    override fun getPathToTile(tile: Tile): List<Tile> {
        if (!containsKey(tile)) {
            Log.debug("PathsToTilesWithinTurn#getPathToTile does not contain $tile: $this")
            throw Exception("$unit Can't reach $tile")
        }
        val reversePathList = ArrayList<Tile>()
        val tileMap = tile.tileMap
        var currentTile = tile
        while (getValue(currentTile).parentTileIdx != currentTile.zeroBasedIndex) {
            reversePathList.add(currentTile)
            currentTile = getValue(currentTile).parentTile(tileMap)
        }
        return reversePathList.reversed()
    }

    override fun contains(tile: Tile): Boolean
        = (this as ImmutableIntIntArrayMap).containsKey(tile.zeroBasedIndex)

    override fun containsKey(tile: Tile): Boolean
        = (this as ImmutableIntIntArrayMap).containsKey(tile.zeroBasedIndex)

    @Readonly fun getValue(tile: Tile): ParentTileAndTotalMovementValue
        = ParentTileAndTotalMovementValue((this as ImmutableIntIntArrayMap).getValue(tile.zeroBasedIndex))
    override fun getMovement(tile: Tile): Float
        = ParentTileAndTotalMovementValue((this as ImmutableIntIntArrayMap).getValue(tile.zeroBasedIndex)).totalMovement
    override fun getParentTile(tile: Tile): Tile
        = ParentTileAndTotalMovementValue((this as ImmutableIntIntArrayMap).getValue(tile.zeroBasedIndex)).parentTile(tile.tileMap)

    @Suppress("OVERRIDE_BY_INLINE")
    override fun forEachTile(op: (Tile) -> Unit) {
        if (isEmpty()) return
        @LocalState
        val reusable = ParentTileAndTotalMovementReusable(atIndex(0))
        forEach { e, _ -> {
            reusable.set(e)
            op(tileMap.tileList[e.key])
        } }
    }

    override fun filter(predicate: (Tile) -> Boolean): PathsToTilesWithinTurn {
        @LocalState
        val builder = Builder(tileMap, unit, size)
        builder.filter(this) { e, _ -> predicate(e.getEntryTile()) }
        return builder.build()
    }
    
    override fun asTileSequence(): Sequence<Tile>
        = sequence { forEach {e,_ -> yield(e.getEntryTile()) } }

    override fun tilesSortedBy(selector: (Tile) -> Float): Sequence<Tile> {
        @LocalState
        val copy = LongArray(size)
        for (i in  0 until size)
            copy[i] = atIndexUnchecked(i).bits
        return copy
            .sortedBy { selector(Entry(it).getEntryTile()) }
            .map { Entry(it).getEntryTile() }
            .asSequence()
    }

    override fun any(): Boolean = (this as ImmutableIntIntArrayMap).any()

    override fun anyTile(predicate: (Tile) -> Boolean): Boolean 
        =  any { e,_ -> predicate(tileMap.tileList[e.key]) }

    override fun <R> firstNotNullTileOfOrNull(mapping: (Tile) -> R?): R? 
        = firstNotNullOfOrNull {e,_ -> mapping(tileMap.tileList[e.key]) }

    override fun minTileByOrNull(selector: (Tile) -> Float): Tile?
        = if (size == 0) null
        else tileMap.tileList[minByDouble { e,_ -> selector(tileMap.tileList[e.key]).toDouble() }.key]

    override fun randomTile(): Tile = atIndex((Math.random() * size).toInt()).getEntryTile()

    class Builder(val tileMap: TileMap, val unit: MapUnit?=null, initialCapacity: Int = 16)
        : AbstractBuilder<Builder, PathsToTilesWithinTurnArrayMap>(initialCapacity), PathsToTilesWithinTurn.Builder {
        override val self get() = this
        override fun build(map: LongArray, size: Int) = PathsToTilesWithinTurnArrayMap(map, size, tileMap, unit)
        override fun set(tile: Tile, parentTile: Tile, totalMovement: Float): PathsToTilesWithinTurn.Builder {
            require(tile.tileMap === tileMap)
            return set(tile.zeroBasedIndex, ParentTileAndTotalMovementValue(parentTile, totalMovement).bits)
        }
        override fun set(tile: Tile, value: ParentTileAndTotalMovementValue): PathsToTilesWithinTurn.Builder {
            require(tile.tileMap === tileMap)
            return set(tile.zeroBasedIndex, value.bits)
        }
    }
    
    companion object {
        @Readonly
        fun of(tile: Tile, value: ParentTileAndTotalMovementValue, unit: MapUnit?=null)
            = PathsToTilesWithinTurnArrayMap(longArrayOf(Entry(tile.zeroBasedIndex, value.bits).bits), 1, tile.tileMap, unit)
        
        class ParentTileAndTotalMovementReusable(
            var value: ParentTileAndTotalMovementValue
        ) :ParentTileAndTotalMovement {
            constructor(e: ImmutableIntIntArrayMap.Companion.Entry) : this(ParentTileAndTotalMovementValue(e.value))
            fun set(e: ImmutableIntIntArrayMap.Companion.Entry) { value = ParentTileAndTotalMovementValue(e.value) }

            override val totalMovement: Float get() = value.totalMovement
            @Readonly override fun parentTile(tileMap: TileMap) = value.parentTile(tileMap)
        }
    }
}

@JvmInline
value class ParentTileAndTotalMovementValue(val bits: Int) :ParentTileAndTotalMovement{
    constructor(parentTileIdx: Int, totalMovement: FixedPointMovement): this(
        (parentTileIdx shl 14) or totalMovement.bits) {
        require(totalMovement <= MAX_MOVEMENT)
    }

    constructor(parentTile: Tile, totalMovement: Float): this(parentTile.zeroBasedIndex,
        fpmFromMovement(totalMovement)
    )
    
    init {
        require(parentTileIdx >= 0)
    }
    
    val parentTileIdx get() = (bits shr 14)
    val totalMovementBits get() = fpmFromFixedPointBits(bits and 0x3FFF)
    override val totalMovement get() = totalMovementBits.toFloat()
    
    @Readonly override fun parentTile(tileMap: TileMap) = tileMap.tileList[parentTileIdx]
    
    override fun toString() = "${javaClass.simpleName}[parentTileIdx=$parentTileIdx totalMovement=$totalMovement]"
    
    companion object {
        val MAX_MOVEMENT = fpmFromFixedPointBits(0x3FFF)
    }
}

