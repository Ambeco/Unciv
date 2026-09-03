package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.UncivGame
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.models.Spy
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.TileMarker
import com.unciv.view.CivView
import com.unciv.view.MapUnitView

/**
 * Every highlight/overlay this file computes is written as a [TileMarker] bit onto the relevant
 * tile's [com.unciv.view.TileView] (via [com.unciv.view.TileView.addMarker]/
 * [com.unciv.view.TileView.setSelectedUnitForFlag]) instead of being pushed directly into a
 * [WorldTileGroup] the way this file used to. A [WorldTileGroup] can be recycled to a completely
 * different tile at any time (see [com.unciv.ui.screens.worldscreen.worldmap.RecyclerWorldMapHolder]'s
 * doc) - pushing a highlight directly into whichever one happens to be pooled for a tile *right now*
 * would silently vanish the moment that tile scrolls off and back on, with nothing to ever redraw it
 * short of the next unrelated `updateTiles` pass (the exact bug arrow overlays had - see
 * [ArrowLifecycle]'s doc - before that was fixed the same way). A [com.unciv.view.TileView] is never
 * recycled, so markers set here are automatically picked up the instant a tile (re)binds, whether or
 * not it happened to be pooled when they were set - see [TileMarker]'s own doc for the full list and
 * [com.unciv.ui.components.tilegroups.layers.TileLayer] subclasses' `doUpdate()` overrides for where
 * they're actually read back and rendered.
 *
 * A consequence: functions here now operate over every tile in [WorldMapHolder.tileMap] for anything
 * that isn't curated to a specific handful of tiles (e.g. [updateTilesForSelectedUnit]'s dim-population
 * pass, [updateTilesForSelectedSpy]'s per-tile loop) - previously bounded to whatever
 * [WorldMapHolder.forEachVisibleTileGroup] currently covers (only the pooled subset, for
 * [RecyclerWorldMapHolder]), matching what [EagerWorldMapHolder] (whose pool already covers every
 * tile) already paid regardless. This only runs on a discrete UI event (a unit/city/spy gets
 * selected), never a per-frame hot path.
 */
object WorldMapTileUpdater {

    private val WorldMapHolder.tileMapView get() = worldScreen.selectedGameView.tileMapView

     fun WorldMapHolder.updateTiles(civView: CivView) {
        val viewingCiv = civView.getCiv()

        if (isMapRevealEnabled(civView)) {
            // Only needs to be done once - this is so the minimap will also be revealed
            forEachVisibleTileGroup {
                it.tile.setExplored(viewingCiv, true)
                it.isForceVisible = true } // So we can see all resources, regardless of tech
        }

        // Recompute every tile's current UI markers *before* the general per-tile update pass below -
        // each tile's own update() (via its layers' doUpdate()) is what actually reads and renders
        // them, so they need to already be current by the time that runs - see this object's own doc.
        tileMapView.resetMarkers()

        // Update tiles according to selected unit/city
        val unitTable = worldScreen.bottomUnitTable
        when {
            unitTable.selectedSpy != null -> {
                updateTilesForSelectedSpy(unitTable.selectedSpy!!)
            }
            unitTable.selectedCity != null -> {
                val city = unitTable.selectedCity!!.getCity()
                updateBombardableTilesForSelectedCity(city)
                // We still want to show road paths to the selected city if they are present
                if (unitTable.selectedUnitIsConnectingRoad) {
                    updateTilesForSelectedUnit(unitTable.selectedUnits[0])
                }
            }
            unitTable.selectedUnit != null -> {
                for (unitView in unitTable.selectedUnits) {
                    updateTilesForSelectedUnit(unitView)
                }
            }
            unitActionOverlays.isNotEmpty() -> {
                removeUnitActionOverlay()
            }
        }

        // Applied last - highest priority, wins over anything else set on the same tile (see
        // TileLayerOverlay.applyMarkers's own doc).
        selectedTile?.addMarker(TileMarker.SELECTED)

        // General update of all tiles - reads back the markers just computed above.
        forEachVisibleTileGroup { it.update(civView) }

        zoom(scaleX) // zoom to current scale, to set the size of the city buttons after "next turn"
    }

    private fun WorldMapHolder.updateTilesForSelectedUnit(unitView: MapUnitView) {
        // Update flags for units which have them
        if (!unitView.isAirUnit()) {
            unitView.getTile().setSelectedUnitForFlag(unitView.getUnit())
        }

        // Fade out less relevant images if a military unit is selected
        if (unitView.isMilitary()) {
            val unit = unitView.getUnit()
            for (tile in tileMap.tileList) {
                val tileView = tileMapView.getTile(tile)

                // Fade out population icons
                tileView.addMarker(TileMarker.DIM_POPULATION)

                val shownImprovementName = tile.getShownImprovement(unit.civ)
                val shownImprovement = unit.civ.gameInfo.ruleset.tileImprovements[shownImprovementName]

                // Fade out improvement icons (but not barb camps or ruins)
                if (shownImprovement != null &&
                    !shownImprovement.isBarbarianCampEquivalent(tile.stateThisTile) &&
                    !shownImprovement.isAncientRuinsEquivalent(unit.cache.state))
                    tileView.addMarker(TileMarker.DIM_IMPROVEMENT)
            }
        }

        // Z-Layer: 0
        // Highlight suitable tiles in swapping-mode
        if (worldScreen.bottomUnitTable.selectedUnitIsSwapping) {
            for (tileView in unitView.getUnitSwappableTiles())
                tileView.addMarker(TileMarker.SWAP_TARGET)
            // In swapping-mode we don't want to show other overlays
            return
        }

        // Z-Layer: 0
        // Highlight suitable tiles in road connecting mode
        if (worldScreen.bottomUnitTable.selectedUnitIsConnectingRoad) {
            if (!unitView.rulesetHasRoadImprovement()) return
            for (tileView in unitView.getValidRoadConnectionTiles())
                tileView.addMarker(TileMarker.ROAD_CONNECT_VALID)

            if (unitConnectRoadPaths.containsKey(unitView)) {
                for (tileView in unitConnectRoadPaths[unitView]!!)
                    tileView.addMarker(TileMarker.ROAD_CONNECT_PATH)
            }

            // In road connecting mode we don't want to show other overlays
            return
        }

        val isAirUnit = unitView.isAirUnit()
        val tilesInMoveRange = unitView.getReachableTilesInCurrentTurn()
        // Prepare special Nuke blast radius display
        val nukeBlastRadius = if (unitView.isNuclearWeapon() && selectedTile != null && selectedTile != unitView.getTile())
            unitView.getNukeBlastRadius() else -1

        // Z-Layer: 1
        // Highlight tiles within movement range
        for (tileView in tilesInMoveRange) {
            // Air-units have additional highlights
            if (isAirUnit && !unitView.isPreparingAirSweep()) {
                if (nukeBlastRadius >= 0 && tileView.aerialDistanceTo(selectedTile!!) <= nukeBlastRadius) {
                    // The tile is within the nuke blast radius
                    tileView.addMarker(TileMarker.AIR_NUKE_BLAST)
                } else if (tileView.aerialDistanceTo(unitView.getTile()) <= unitView.getRange()) {
                    // The tile is within attack range
                    tileView.addMarker(TileMarker.AIR_ATTACK_RANGE)
                } else if (unitView.isExplored(tileView) && tileView.aerialDistanceTo(unitView.getTile()) <= unitView.getRange()*2) {
                    // The tile is within move range
                    tileView.addMarker(if (unitView.canMoveTo(tileView)) TileMarker.AIR_MOVE_RANGE_OK else TileMarker.AIR_MOVE_RANGE_BLOCKED)
                }
            }

            // Highlight tile unit can move to
            if (unitView.canMoveTo(tileView) ||
                unitView.isUnknownTileWeShouldAssumeToBePassable(tileView) && !isAirUnit
            ) {
                tileView.addMarker(TileMarker.MOVABLE_TO)
                if (unitView.isPreparingParadrop()) tileView.addMarker(TileMarker.MOVABLE_TO_PARADROP)
            }
        }

        // Z-Layer: 2
        // Add back in the red markers for Air Unit Attack range since they can't move, but can still attack
        if (unitView.cannotMove() && isAirUnit && !unitView.isPreparingAirSweep()) {
            for (tileView in unitView.getTilesInAttackRange()) {
                // The tile is within attack range
                tileView.addMarker(TileMarker.AIR_ATTACK_ONLY)
            }
        }

        // Z-Layer: 3
        // Movement paths
        if (unitMovementPaths.containsKey(unitView)) {
            for (tileView in unitMovementPaths[unitView]!!) {
                tileView.addMarker(TileMarker.MOVEMENT_PATH)
            }
        }

        // Z-Layer: 4
        // Highlight road path for workers currently connecting roads
        if (unitView.isAutomatingRoadConnection()) {
            val futureTiles = unitView.getFutureAutomatedRoadConnectionTiles() ?: return
            for (tileView in futureTiles) {
                tileView.addMarker(TileMarker.ROAD_AUTOMATION_FUTURE)
            }
        }

        // Z-Layer: 5
        // Highlight movement destination tile
        if (unitView.isMoving()) {
            unitView.getMovementDestination().addMarker(TileMarker.MOVEMENT_DESTINATION)
        }

        // Z-Layer: 6
        // Highlight attackable tiles
        if (unitView.isMilitary()) {
            val unit = unitView.getUnit()

            val attackableTiles: List<AttackableTile> =
                if (nukeBlastRadius >= 0)
                    selectedTile!!.getTile().getTilesInDistance(nukeBlastRadius)
                        // Should not display invisible submarine units even if the tile is visible.
                        .filter { targetTile -> (targetTile.isVisible(unit.civ) && targetTile.getUnits().any { !it.isInvisible(unit.civ) })
                                || (targetTile.isCityCenter() && unit.civ.hasExplored(targetTile)) }
                        .map { AttackableTile(unit.getTile(), it, 1f, null) }
                        .toList()
                else TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
                    .filter { it.tileToAttack.isVisible(unit.civ) }
                    .distinctBy { it.tileToAttack }

            for (attackableTile in attackableTiles) {
                val tileViewToAttack = tileMapView.getTile(attackableTile.tileToAttack)
                tileViewToAttack.addMarker(TileMarker.ATTACKABLE)
                // the targets which cannot be attacked without movements shown as orange-ish
                if (attackableTile.tileToAttackFrom != unit.currentTile)
                    tileViewToAttack.addMarker(TileMarker.ATTACKABLE_NEEDS_MOVE)
                if (attackableTile.tileToAttack == selectedTile?.getTile())
                    tileMapView.getTile(attackableTile.tileToAttackFrom).addMarker(TileMarker.ATTACK_SOURCE)
            }
        }

        // Z-Layer: 7
        // Highlight best tiles for city founding
        if (unitView.hasUnique(UniqueType.FoundCity)
            && UncivGame.Current.settings.showSettlersSuggestedCityLocations) {
            val unit = unitView.getUnit()
            CityLocationTileRanker.getBestTilesToFoundCity(unit, 5, minimumValue = 50f).tileRankMap.asSequence()
                .filter { it.key.isExplored(unit.civ) }.sortedByDescending { it.value }.take(3).forEach {
                    tileMapView.getTile(it.key).addMarker(TileMarker.SUGGESTED_CITY_SITE)
                }
        }
    }

    private fun WorldMapHolder.updateTilesForSelectedSpy(spy: Spy) {
        for (tile in tileMap.tileList) {
            val tileView = tileMapView.getTile(tile)
            // Every tile's own highlight/crosshair/good-city-location-indicator is already reset by
            // resetMarkers() (nothing here sets any of those markers), matching the old
            // layerOverlay.reset() call this replaces.
            if (!tile.isCityCenter())
                tileView.addMarker(TileMarker.DIM_IMPROVEMENT)
            tileView.addMarker(TileMarker.SPY_DIM_MODE)
        }
        for (city in worldScreen.gameInfo.getCities()) {
            if (spy.canMoveTo(city)) {
                tileMapView.getTile(city.getCenterTile()).addMarker(TileMarker.SPY_TARGET_CITY)
            }
        }
    }

    private fun WorldMapHolder.updateBombardableTilesForSelectedCity(city: City) {
        if (!city.canBombard()) return
        for (tile in TargetHelper.getBombardableTiles(city)) {
            tileMapView.getTile(tile).addMarker(TileMarker.BOMBARDABLE)
        }
    }
}
