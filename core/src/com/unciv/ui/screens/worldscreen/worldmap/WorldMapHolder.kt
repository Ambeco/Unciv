package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.view.TileView
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.widgets.ZoomableScrollable
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * Public contract for the widget [WorldScreen] scrolls/zooms to show the map: querying/selecting
 * tiles, jumping the view to a location, and clearing whatever action-button overlay is currently
 * up. This is the surface genuinely-external code (other screens/tables that just want to observe
 * or command the map from outside the world-map subsystem itself, e.g. [Minimap][com.unciv.ui.screens.worldscreen.minimap.Minimap],
 * `BattleTable`, `NotificationActions`) is meant to hold a reference typed as - see [WorldScreen.mapHolder]'s
 * own doc for how that's enforced.
 *
 * Deliberately never exposes an [Actor] anywhere in its own surface (param, return type, or field) -
 * unlike [AbstractWorldMapHolder], which is trusted with far more: hanging arbitrary Actors (arrows,
 * unit-action-button overlays, the animated "steal the sprite and walk it over" unit-movement
 * effect) directly onto whichever [WorldMapHolder] implementation is live. A raw Actor pushed onto a
 * *pooled* implementation like [RecyclerWorldMapHolder] can silently vanish the moment its owning
 * tile gets recycled to a different tile mid-scroll - exactly the class of bug this split exists to
 * keep away from every caller that doesn't already know to worry about it.
 *
 * See [AbstractWorldMapHolder]'s own doc for why the shared-implementation surface lives on a
 * second, richer interface instead of here directly.
 */
interface WorldMapHolder : ZoomableScrollable {
    val worldScreen: WorldScreen
    val tileMap: TileMap

    var selectedTile: TileView?

    var currentTileSetStrings: TileSetStrings

    /**
     * Direct single-tile lookup - the [TileView] overload of [tileGroupOf], for a caller that
     * already knows exactly which tile it wants (as opposed to the [Actor] overload, which resolves
     * a scene-graph hit target back to its owning group). Returns `null` if this holder doesn't
     * currently have a live view for [tileView] - always true for [EagerWorldMapHolder] (every tile
     * in [tileMap], permanently), only true for tiles near the viewport for a pooled implementation.
     *
     * Deliberately not a `Map`/`Collection` property: every real external caller (`BattleTable`,
     * `BattleTableHelpers`, `TileInfoTable`) only ever needs one tile at a time, and exposing the
     * whole backing structure would have made
     * [RecyclerWorldMapHolder]'s own version needlessly expensive - its previous `tileGroups: Map`
     * property rebuilt a fresh `Map` from scratch on *every* access (even a single lookup), where
     * this can resolve directly. For the few callers that genuinely need every currently-live group,
     * see [AbstractWorldMapHolder.forEachVisibleTileGroup].
     */
    fun tileGroupOf(tileView: TileView): WorldTileGroup?

    /** Recomputes zoom limits from settings/map size - see [EagerWorldMapHolder]'s implementation
     *  for the map-width-based zoom-out limit this adds on top of the backing scroll surface's own
     *  default (settings-only) zoom-limit logic. */
    fun reloadMaxZoom()

    /**
     * Clears whatever action-button overlay (move-here/swap-with/connect-road/move-spy) is
     * currently up - called by callers as unrelated as `BattleTable` (clearing an attack-selection
     * overlay once an attack resolves) and `UnitActionsTable`. No default here: the actual overlay
     * Actors this clears live in [AbstractWorldMapHolder.unitActionOverlays], which this interface
     * deliberately doesn't expose - see [AbstractWorldMapHolder.removeUnitActionOverlay] for the
     * real implementation every [WorldMapHolder] gets for free by way of extending
     * [AbstractWorldMapHolder].
     */
    fun removeUnitActionOverlay()

    /** Scrolls the world map to specified coordinates. No default here: [RecyclerWorldMapHolder]
     *  overrides this entirely regardless (see its own doc for why the shared default's approach -
     *  finding an already-bound tile via [tileGroupOf] - doesn't fit a pooled implementation), so the
     *  shared default this interface *could* otherwise offer only actually gets shared by
     *  [EagerWorldMapHolder] - see [AbstractWorldMapHolder.setCenterPosition] for that default.
     * @param vector Position to center on
     * @param immediately Do so without animation
     * @param selectUnit Select a unit at the destination
     * @return `true` if scroll position was changed, `false` otherwise
     */
    fun setCenterPosition(vector: HexCoord, immediately: Boolean = false, selectUnit: Boolean = true, forceSelectUnit: MapUnit? = null): Boolean
}
