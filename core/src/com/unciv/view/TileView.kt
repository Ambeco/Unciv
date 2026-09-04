package com.unciv.view

import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.stats.Stats
import com.unciv.utils.DebugUtils
import yairm210.purity.annotations.Readonly

/** View of a [Tile] from the perspective of [viewer] via [tileMapView]. */
class TileView internal constructor(private val tile: Tile, val tileMapView: TileMapView,
               viewer: Civilization?,
               spectatorMode: Boolean = false) : View<Tile>(tile, viewer, spectatorMode) {

    /**
     * Bitmask of [TileMarker] flags - transient UI decoration state (current selection highlights,
     * overlays, dimming, city-button state, etc.), computed fresh by
     * [com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater] on every relevant game/UI
     * event and read directly by each concerned
     * [com.unciv.ui.components.tilegroups.layers.TileLayer]'s own `doUpdate()` (the same call every
     * bind/rebind already makes for ordinary tile content like terrain or resources).
     *
     * Deliberately stored here rather than pushed into whichever [com.unciv.ui.components.tilegroups.WorldTileGroup]
     * happens to be pooled for this tile *right now*: a pooled group can be recycled to a completely
     * different tile at any time, silently dropping anything pushed directly onto it, with nothing to
     * ever redraw it short of the next unrelated full-update pass - the exact bug arrow overlays had
     * (see [com.unciv.ui.screens.worldscreen.worldmap.ArrowLifecycle]'s doc) before this. A [TileView]
     * is never "recycled" - it's the same instance for a given [Tile] for as long as [tileMapView]
     * lives (see [TileMapView.getTile]'s caching) - so markers set here are automatically picked up
     * the instant a tile (re)binds, whether or not it happened to be pooled when they were set.
     *
     * Only ever mutated via [addMarker]/[TileMapView.resetMarkers] - never assigned directly - so
     * [TileMapView] can track which tiles actually have a marker set (see
     * [TileMapView.resetMarkers]'s own doc for why that matters).
     */
    var markers: Int = 0
        internal set

    fun hasMarker(flag: Int) = markers and flag != 0

    /** Sets [flag] on this tile's [markers] - see that property's own doc. */
    fun addMarker(flag: Int) = tileMapView.addMarker(this, flag)

    /** The [MapUnit] whose flag icon should currently render as "selected" on this tile - not
     *  itself a [TileMarker] bit, since it needs an actual [MapUnit] reference rather than a plain
     *  boolean, but tracked/reset alongside [markers] all the same - see
     *  [TileMapView.resetMarkers]. */
    var selectedUnitForFlag: MapUnit? = null
        internal set

    /** Sets [unit] as this tile's [selectedUnitForFlag] - see that property's own doc. */
    fun setSelectedUnitForFlag(unit: MapUnit?) = tileMapView.setSelectedUnitForFlag(this, unit)

    /** Pairs a [TileSingleAnimation] with the real time ([System.currentTimeMillis]) it started -
     *  see [TileView.playingAnimation]'s own doc for why these two are bundled into one property
     *  instead of living as two separate ones a reader could see out of sync. */
    data class PlayingAnimation(val animation: TileSingleAnimation, val startTimeMillis: Long)

    /**
     * Which one-shot animation (if any) is currently playing on this tile, and when it started -
     * see [playAnimation]. Unlike [markers] (persistent "current state", recomputed fresh and
     * cleared by [TileMapView.resetMarkers] every `updateTiles()` pass), this is edge-triggered and
     * deliberately *not* touched by [TileMapView.resetMarkers] - clearing it is [clearAnimation]'s
     * job, called either by whichever [com.unciv.ui.components.tilegroups.layers.TileLayer] renders
     * it once elapsed real time exceeds [TileSingleAnimation.totalDurationSeconds], or by whatever
     * triggered [playAnimation] in the first place, to cancel it early (e.g.
     * [com.unciv.ui.screens.worldscreen.WorldScreen.switchToNextUnit] stopping a "look here" blink
     * once there's no longer a next unit to look at). Nothing *else* should call it - doing so would
     * kill an unrelated in-progress animation early.
     *
     * Storing (animation, start time) rather than a bare "is playing" flag is what lets the
     * animation resume correctly mid-flight if this tile scrolls out of a pooled implementation's
     * view and back in before it finishes: the renderer recomputes "what should this look like right
     * now" purely from elapsed real time on every (re)bind, rather than needing some Actor/Action to
     * have stayed alive continuously - the same problem (and the same fix) [markers] has for
     * persistent highlights, just edge- rather than level-triggered.
     */
    var playingAnimation: PlayingAnimation? = null
        private set

    /** Which of [TileLayerUnitSprite][com.unciv.ui.components.tilegroups.layers.TileLayerUnitSprite]'s
     *  two slots [CombatFlashRed] should flash, or `null` to flash
     *  [TileLayerImprovement][com.unciv.ui.components.tilegroups.layers.TileLayerImprovement]'s icon
     *  instead (the combatant was a city, not a unit) - set alongside [playingAnimation] by
     *  [playCombatFlash], since a bare "this tile has a flash" flag can't say which of a tile's
     *  several possible Actors (a stacked civilian *and* military unit, or a city's improvement
     *  icon) it actually targets. */
    var combatFlashUnit: MapUnit? = null
        private set

    /** Starts [animation] playing on this tile, from now - see [playingAnimation]'s own doc. */
    fun playAnimation(animation: TileSingleAnimation) {
        playingAnimation = PlayingAnimation(animation, System.currentTimeMillis())
    }

    /** Starts [CombatFlashRed] on this tile, targeting [unit]'s own sprite slot - or this tile's
     *  improvement icon, if [unit] is `null` (the combatant was a city) - see [combatFlashUnit]'s
     *  own doc. */
    fun playCombatFlash(unit: MapUnit?) {
        playAnimation(CombatFlashRed)
        combatFlashUnit = unit
    }

    /** Stops whatever [playingAnimation] is currently playing, whether because it finished
     *  naturally or because a caller wants to cancel it early - see that property's own doc. */
    fun clearAnimation() {
        playingAnimation = null
    }

    // Navigation
    @Readonly fun getTile(): Tile = tile
    @Readonly fun getCivView(): CivView? = tileMapView.gameView?.civView
    @Readonly fun owningCity(): ForeignCityView? {
        val city = tile.owningCity ?: return null
        return toForeignCityView(city)
    }
    @Readonly fun getWorkingCity(): ForeignCityView? {
        val city = tile.getWorkingCity() ?: return null
        return toForeignCityView(city)
    }
    @Readonly private fun toForeignCityView(city: City): ForeignCityView? {
        val viewer = viewer ?: return null
        val gameView = tileMapView.gameView ?: return null
        return ForeignCityView(city, viewer, spectatorMode, gameView)
    }
    @Readonly fun getOwner(): ForeignCivView? {
        val owner = tile.getOwner() ?: return null
        if (viewer == null) return null
        val gameView = tileMapView.gameView ?: return null
        return gameView.getForeignCivView(owner)
    }
    @Readonly private fun isVisible(unit: MapUnit): Boolean {
        if (viewer == null) return false
        return DebugUtils.VISIBLE_MAP || unit.isVisibleTo(viewer)
    }
    @Readonly private fun toForeignMapUnitView(unit: MapUnit): ForeignMapUnitView =
        tileMapView.gameView!!.getForeignMapUnitView(unit)
    val civilianUnit: ForeignMapUnitView?
        get() {
            val unit = tile.civilianUnit ?: return null
            if (!isVisible(unit)) return null
            return toForeignMapUnitView(unit)
        }
    val militaryUnit: ForeignMapUnitView?
        get() {
            val unit = tile.militaryUnit ?: return null
            if (!isVisible(unit)) return null
            return toForeignMapUnitView(unit)
        }
    @Readonly fun getVisibleUnits(): List<ForeignMapUnitView> {
        if (viewer == null) return emptyList()
        return tile.getUnits()
            .filter { isVisible(it) }
            .map { toForeignMapUnitView(it) }
            .toList()
    }
    @Readonly fun getCombatant(): CombatantView? {
        val viewer = viewer ?: return null
        if (!isExplored()) return null
        val gameView = tileMapView.gameView ?: return null
        if (tile.isCityCenter())
            return CombatantView(CityCombatant(tile.getCity()!!), viewer, spectatorMode, gameView)

        val militaryUnit = tile.militaryUnit
        if (militaryUnit != null && isVisible(militaryUnit))
            return CombatantView(MapUnitCombatant(militaryUnit), viewer, spectatorMode, gameView)
        val civilianUnit = tile.civilianUnit
        if (civilianUnit != null && isVisible(civilianUnit))
            return CombatantView(MapUnitCombatant(civilianUnit), viewer, spectatorMode, gameView)
        return null
    }

    // Data retrieval
    @Readonly fun position() = tile.position
    /** Ideally this function should not exist - you should never be able to get a tileview of an unexplored tile
     * However, currently the way the map works is we set up a tilegroup for all players and use the tileview for that tile
     * That means that *in order to allow clicking on an unexplored tile* we currently need to accept tileviews of unexplored tiles
     * */
    @Readonly fun isExplored() = viewer == null || tile.isExplored(viewer)
    @Readonly fun getVisibleNeighbors(): Sequence<TileView> =
        tile.neighbors
            .filter { viewer == null || it.isExplored(viewer) }
            .map { tileMapView.getTile(it) }
    @Readonly fun getVisibleTilesInDistance(distance: Int): Sequence<TileView> =
        tile.getTilesInDistance(distance)
            .filter { viewer == null || it.isExplored(viewer) }
            .map { tileMapView.getTile(it) }

    @Readonly fun isCityCenter(): Boolean = tile.isCityCenter()
    @Readonly fun isWorked(): Boolean = tile.isWorked()
    @Readonly fun isBlockaded(): Boolean = tile.isBlockaded()
    @Readonly fun providesYield(): Boolean = tile.providesYield()
    @Readonly fun isLocked(): Boolean = tile.isLocked()
    @Readonly fun isImpassible(): Boolean = tile.isImpassible()
    @Readonly fun isAdjacentTo(terrainFilter: String): Boolean = tile.isAdjacentTo(terrainFilter)
    @Readonly fun getDefensiveBonus(): Float = tile.getDefensiveBonus()
    @Readonly fun aerialDistanceTo(other: TileView): Int = tile.aerialDistanceTo(other.unwrap())
    @Readonly fun getShownImprovement(): String? = tile.getShownImprovement(viewer)

    val baseTerrain: String get() = tile.baseTerrain
    val terrainFeatures: List<String> get() = tile.terrainFeatures
    @Readonly fun getViewableResource(viewingCiv: CivView?): TileResource? {
        val resource = tile.tileResource ?: return null
        return if (viewingCiv == null || viewingCiv.canSeeResource(resource)) resource else null
    }
    val resource: String? get() = tile.resource
    val resourceAmount: Int get() = tile.resourceAmount
    val naturalWonder: String? get() = tile.naturalWonder
    val roadStatus: RoadStatus get() = tile.roadStatus
    val roadIsPillaged: Boolean get() = tile.roadIsPillaged
    val improvementIsPillaged: Boolean get() = tile.improvementIsPillaged
    val improvementInProgress: String? get() = tile.improvementInProgress
    val improvement: String? get() = tile.improvement
    val tileImprovement: TileImprovement? get() = tile.tileImprovement
    val turnsToImprovement: Int get() = tile.turnsToImprovement
    @Readonly fun isMarkedForCreatesOneImprovement(): Boolean = tile.isMarkedForCreatesOneImprovement()

    val isLand: Boolean get() = tile.isLand
    val hasBottomRightRiver: Boolean get() = tile.hasBottomRightRiver
    val hasBottomRiver: Boolean get() = tile.hasBottomRiver
    val hasBottomLeftRiver: Boolean get() = tile.hasBottomLeftRiver
    @Readonly fun isPillaged(): Boolean = tile.isPillaged()
    @Readonly fun getBaseTerrain(): Terrain = tile.getBaseTerrain()
    @Readonly fun getRuleset(): Ruleset = tile.ruleset

    @Readonly fun getTileStats(viewingCiv: CivView?, cityView: CityView? = null): Stats {
        val city = cityView?.unwrap() ?: tile.getCity()
        return tile.stats.getTileStats(city, viewingCiv?.unwrap())
    }
    @Readonly fun getTileStatsBreakdown(
        viewingCiv: CivView?,
        cityView: CityView? = null,
    ): List<Pair<String, Stats>> {
        val city = cityView?.unwrap() ?: tile.getCity()
        return tile.stats.getTileStatsBreakdown(city, viewingCiv?.unwrap())
    }
    @Readonly fun providesResources(viewingCiv: CivView): Boolean = tile.providesResources(viewingCiv.unwrap())

    @Readonly fun getTileMap(): TileMapView = tileMapView

    companion object {
        /** For icon/preview rendering of a single tile that has no backing [TileMap]. */
        fun forSingleTile(tile: Tile): TileView {
            val tileMap = TileMap(1).also { it.tileList.add(tile) }
            return TileMapView(tileMap, null, false).getTile(tile)
        }
    }
}
