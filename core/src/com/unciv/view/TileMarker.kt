package com.unciv.view

/**
 * Bit flags for [TileView.markers] - see that property's own doc for why this exists at all
 * (surviving tile recycling for free, the same way ordinary tile content already does) and
 * [com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater] for where these actually get set.
 *
 * Almost every one of these is a plain independent toggle; the few exceptions are noted individually
 * and are still resolved by simple bit-priority checks (matching the equivalent sequential
 * show-then-overwrite calls the pre-marker code made, in the same order), not by any richer stored
 * value - see [MOVABLE_TO_PARADROP]/[AIR_NUKE_BLAST]/[ATTACKABLE_NEEDS_MOVE]'s own docs.
 */
object TileMarker {
    /** The currently selected tile/unit's own tile - Color.WHITE highlight. Applied last, i.e. wins
     *  over every other highlight on the same tile - matches the pre-marker code, which applied this
     *  one strictly after everything else in [WorldMapTileUpdater.updateTiles]. */
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
     *  on the same tile (matches the pre-marker code applying this one second). */
    const val ROAD_CONNECT_PATH = 1 shl 5
    /** The selected (non-air, or air-and-can-still-move) unit can move here this turn. */
    const val MOVABLE_TO = 1 shl 6
    /** Modifies [MOVABLE_TO]'s color: the selected unit is paradropping (blue) rather than a plain
     *  move (white). Meaningless without [MOVABLE_TO] also set. */
    const val MOVABLE_TO_PARADROP = 1 shl 7
    /** Z-Layer 2: the selected air unit can't move but can still attack from here. */
    const val AIR_ATTACK_ONLY = 1 shl 8
    /** The selected air unit's nuke would hit this tile - takes priority over [AIR_ATTACK_RANGE]/
     *  [AIR_MOVE_RANGE_OK]/[AIR_MOVE_RANGE_BLOCKED] (checked in this same descending order, matching
     *  the pre-marker code's if/else-if chain) when more than one of this group is set. */
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
    /** Spy-selection view is active: nudges this tile's city button down - set on every tile,
     *  matching the pre-marker code's unconditional loop over all of them (improvement dimming in
     *  that same loop is [DIM_IMPROVEMENT] instead - visually identical to the military-selection
     *  case, but only set for non-city-center tiles, unlike this one). */
    const val SPY_DIM_MODE = 1 shl 21
    /** The selected city can bombard this tile. */
    const val BOMBARDABLE = 1 shl 22
}
