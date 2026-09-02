package com.unciv.utils

import com.unciv.logic.map.HexCoord

object DebugUtils {

    /**
     * This exists so that when debugging we can see the entire map.
     * Remember to turn this to false before commit and upload!
     * Or use the "secret" debug page of the options popup instead.
     */
    var VISIBLE_MAP: Boolean = false

    /** This flag paints the tile coordinates directly onto the map tiles. */
    var SHOW_TILE_COORDS: Boolean = false
    
    var SHOW_TILE_IMAGE_LOCATIONS: Boolean = false

    /** This flag paints the last computed settler tile ranking onto the map tiles. */
    var SHOW_SETTLER_SCORES: Boolean = false

    /** Last settler tile ranking computed by CityLocationTileRanker, for [SHOW_SETTLER_SCORES]. */
    var SETTLER_SCORES: Map<HexCoord, Float> = emptyMap()

    /** For when you need to test something in an advanced game and don't have time to faff around */
    var SUPERCHARGED: Boolean = false

    /** Simulate until this turn on the first "Next turn" button press.
     *  Does not update World View changes until finished.
     *  Set to 0 to disable.
     */
    var SIMULATE_UNTIL_TURN: Int = 0

    /** For A/B testing against the unchanged AI.
     *  Gate experimental code with `if (civInfo.civID in DebugUtils.CIV_IDS_IN_EXPERIMENT_GROUP)`.
     */
    var CIV_IDS_IN_EXPERIMENT_GROUP: Set<String> = emptySet()

    /** Use [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder] (a fixed-size pool of
     *  [com.unciv.ui.components.tilegroups.WorldTileGroup]s, rebound as the viewport scrolls) instead
     *  of [com.unciv.ui.screens.worldscreen.worldmap.EagerWorldMapHolder] (one permanently alive per
     *  map tile) for the world map. Debug-gated rather than a persisted [com.unciv.models.metadata.GameSettings]
     *  setting while it's still missing full parity with [com.unciv.ui.screens.worldscreen.worldmap.EagerWorldMapHolder]
     *  (e.g. bulk map-reveal only covers the pool, not every tile) - see
     *  [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder]'s class doc for the full list.
     *  Surfaced as a checkbox on Options > Display's Experimental section (not [com.unciv.ui.popups.options.DebugTab] -
     *  it's meant to be easy to find for testing) - toggling it there reloads the world screen for you; flipping
     *  this field directly anywhere else needs a manual world screen reload to take effect, since it's
     *  only read once, at [com.unciv.ui.screens.worldscreen.WorldScreen] construction. */
    var USE_RECYCLER_WORLD_MAP: Boolean = false

}
