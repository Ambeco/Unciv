package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.logic.map.tile.Tile
import com.unciv.ui.components.MapArrowType

/**
 * Tracks which arrows should currently be considered "live" (visible), given each endpoint tile's
 * own independent bind/unbind lifecycle in a recycled pool - see [RecyclerWorldMapHolder]'s arrow
 * overlay for the motivating case ([addArrow]/[resetArrows]) and its own doc for why an arrow's
 * lifetime can't just be "however long its *source* tile happens to have a live [WorldTileGroup]
 * bound to it", the way [EagerWorldMapHolder] gets away with. An [ArrowSpec] is live as long as *at
 * least one* of its two endpoint tiles is currently bound - [ArrowSpec.boundEndpoints] tracks the
 * count (0, 1, or 2) directly rather than deriving it, so a tile leaving on one side never has to
 * ask "is my *other* endpoint still bound?" itself.
 *
 * Deliberately decoupled from any Actor/RecyclerView/[WorldTileGroup] concept - a live [ArrowSpec]
 * is reported via [onActivated]/[onDeactivated] callbacks the caller supplies, with no opinion on
 * what those actually do (create/position/remove an Image, in [RecyclerWorldMapHolder]'s case) -
 * which is what lets this be unit-tested with nothing but plain [Tile]s, no [WorldTileGroup]/
 * `CivView`/`RecyclerView` needed at all.
 */
class ArrowLifecycle(
    private val onActivated: (ArrowSpec) -> Unit,
    private val onDeactivated: (ArrowSpec) -> Unit
) {
    class ArrowSpec(val from: Tile, val to: Tile, val type: MapArrowType) {
        var boundEndpoints: Int = 0
            internal set
    }

    /** Every tracked [ArrowSpec], indexed by *both* [ArrowSpec.from] and [ArrowSpec.to] (a spec's
     *  two tiles always differ - see [add]'s guard) - so [onTileBound]/[onTileUnbound] can look
     *  either endpoint up directly, in O(1), instead of scanning every tracked spec. Both keys'
     *  lists reference the *same* [ArrowSpec] instance, so mutating it through one is visible
     *  through the other. */
    private val byTile = HashMap<Tile, MutableList<ArrowSpec>>()

    private fun allSpecs(): Set<ArrowSpec> {
        val all = HashSet<ArrowSpec>()
        for (specs in byTile.values) all.addAll(specs)
        return all
    }

    /** Discards every tracked arrow, deactivating whichever ones were still live. */
    fun reset() {
        for (spec in allSpecs()) if (spec.boundEndpoints > 0) onDeactivated(spec)
        byTile.clear()
    }

    /** Starts tracking a new arrow from [from] to [to]. [isBound] is asked, right now, whether each
     *  endpoint is *currently* bound - mirrors what [onTileBound] would do had this tile become
     *  bound *after* this arrow already existed, so an arrow added while its source (or target) is
     *  already on-screen shows up immediately instead of waiting for some later bind event that may
     *  never come for a tile that's already settled in the pool. */
    fun add(from: Tile, to: Tile, type: MapArrowType, isBound: (Tile) -> Boolean) {
        if (from.position == to.position) return // matches TileLayerMisc.addArrow's own self-arrow guard
        val spec = ArrowSpec(from, to, type)
        byTile.getOrPut(from) { mutableListOf() }.add(spec)
        byTile.getOrPut(to) { mutableListOf() }.add(spec)
        spec.boundEndpoints = (if (isBound(from)) 1 else 0) + (if (isBound(to)) 1 else 0)
        if (spec.boundEndpoints > 0) onActivated(spec)
    }

    /** [tile] just became bound to *some* holder - every arrow touching it gains a bound endpoint,
     *  activating on the 0->1 transition (its *other* endpoint, if any, was already accounted for). */
    fun onTileBound(tile: Tile) {
        for (spec in byTile[tile].orEmpty()) {
            val wasInactive = spec.boundEndpoints == 0
            spec.boundEndpoints++
            if (wasInactive) onActivated(spec)
        }
    }

    /** [tile] just stopped being bound to whatever holder it had - every arrow touching it loses a
     *  bound endpoint, deactivating once neither endpoint is bound anymore. */
    fun onTileUnbound(tile: Tile) {
        for (spec in byTile[tile].orEmpty()) {
            spec.boundEndpoints--
            if (spec.boundEndpoints <= 0) onDeactivated(spec)
        }
    }
}
