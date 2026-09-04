package com.unciv.view

/**
 * Bit flags for [TileView.overlays] - see that property's own doc for why these survive tile
 * recycling for free. Almost all are independent toggles; the few exceptions are resolved by
 * simple bit-priority checks (matching the old sequential show-then-overwrite calls, in the same
 * order) - see [MOVABLE_TO_PARADROP]/[AIR_NUKE_BLAST]/[ATTACKABLE_NEEDS_MOVE].
 */
object TileOverlay {
    /** The currently selected tile/unit's own tile - Color.WHITE highlight. Wins over every other
     *  highlight on the same tile - applied last in [WorldMapTileUpdater.updateTiles]. */
    const val SELECTED = 1 shl 0
    /** A military unit is selected - dims this tile's worked-population icon. */
    const val DIM_POPULATION = 1 shl 1
    /** A military unit is selected and this tile's improvement isn't a barb camp/ancient ruin -
     *  dims this tile's improvement icon. */
    const val DIM_IMPROVEMENT = 1 shl 2
    /** Valid target for the selected unit's unit-swap. */
    const val SWAP_TARGET = 1 shl 3
    /** Valid target for the selected unit's road-connection order. */
    const val ROAD_CONNECT_VALID = 1 shl 4
    /** Tile on the selected unit's in-progress road-connection path - overrides [ROAD_CONNECT_VALID]
     *  on the same tile. */
    const val ROAD_CONNECT_PATH = 1 shl 5
    /** The selected (non-air, or air-and-can-still-move) unit can move here this turn. */
    const val MOVABLE_TO = 1 shl 6
    /** Modifies [MOVABLE_TO]'s color: the selected unit is paradropping (blue) rather than a plain
     *  move (white). Meaningless without [MOVABLE_TO] also set. */
    const val MOVABLE_TO_PARADROP = 1 shl 7
    /** Z-Layer 2: the selected air unit can't move but can still attack from here. */
    const val AIR_ATTACK_ONLY = 1 shl 8
    /** The selected air unit's nuke would hit this tile - takes priority over [AIR_ATTACK_RANGE]/
     *  [AIR_MOVE_RANGE_OK]/[AIR_MOVE_RANGE_BLOCKED] (checked in this descending order) if more than
     *  one of this group is set. */
    const val AIR_NUKE_BLAST = 1 shl 9
    /** The selected air unit could attack this tile from its current position. */
    const val AIR_ATTACK_RANGE = 1 shl 10
    /** The selected air unit could move here (and is explored). */
    const val AIR_MOVE_RANGE_OK = 1 shl 11
    /** The selected air unit could reach here, but can't actually move onto it. */
    const val AIR_MOVE_RANGE_BLOCKED = 1 shl 12
    /** On the selected unit's stored (already-executed) movement path this turn. */
    const val MOVEMENT_PATH = 1 shl 13
    /** On the selected worker's automated-road-connection future path. */
    const val ROAD_AUTOMATION_FUTURE = 1 shl 14
    /** The selected unit's current movement destination. */
    const val MOVEMENT_DESTINATION = 1 shl 15
    /** The selected military unit can attack this tile. */
    const val ATTACKABLE = 1 shl 16
    /** Modifies [ATTACKABLE]'s crosshair alpha: attacking this tile needs the unit to move first
     *  (0.5 alpha) rather than attack in place (1.0). Meaningless without [ATTACKABLE] also set. */
    const val ATTACKABLE_NEEDS_MOVE = 1 shl 17
    /** The tile the selected unit would attack *from* to hit the currently-selected target tile. */
    const val ATTACK_SOURCE = 1 shl 18
    /** One of the selected settler's suggested city-founding sites. */
    const val SUGGESTED_CITY_SITE = 1 shl 19
    /** A city the selected spy can move to. */
    const val SPY_TARGET_CITY = 1 shl 20
    /** Spy-selection view: nudges this tile's city button down - set on every tile (improvement
     *  dimming in the same pass is [DIM_IMPROVEMENT] instead, only for non-city-center tiles). */
    const val SPY_DIM_MODE = 1 shl 21
    /** The selected city can bombard this tile. */
    const val BOMBARDABLE = 1 shl 22
}
