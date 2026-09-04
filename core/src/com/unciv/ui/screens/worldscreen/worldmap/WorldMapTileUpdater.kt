package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.UncivGame
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.models.Spy
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.CivView
import com.unciv.view.MapUnitView
import com.unciv.view.TileOverlay

// Every highlight/overlay here is written as a TileOverlay bit onto the tile's TileView instead of
// pushed directly into a WorldTileGroup - see TileOverlay's own doc for why. One consequence: most
// functions below now iterate every tile in tileMap, not just the pooled/visible subset.
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

        // Recompute every tile's current UI overlays *before* the general per-tile update pass below -
        // each tile's own update() (via its layers' doUpdate()) is what actually reads and renders
        // them, so they need to already be current by the time that runs - see this object's own doc.
        tileMapView.resetOverlays()

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
        // TileLayerOverlay.applyOverlays's own doc).
        selectedTile?.addOverlay(TileOverlay.SELECTED)

        // General update of all tiles - reads back the overlays just computed above.
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
                tileView.addOverlay(TileOverlay.DIM_POPULATION)

                val shownImprovementName = tile.getShownImprovement(unit.civ)
                val shownImprovement = unit.civ.gameInfo.ruleset.tileImprovements[shownImprovementName]

                // Fade out improvement icons (but not barb camps or ruins)
                if (shownImprovement != null &&
                    !shownImprovement.isBarbarianCampEquivalent(tile.stateThisTile) &&
                    !shownImprovement.isAncientRuinsEquivalent(unit.cache.state))
                    tileView.addOverlay(TileOverlay.DIM_IMPROVEMENT)
            }
        }

        // Z-Layer: 0
        // Highlight suitable tiles in swapping-mode
        if (worldScreen.bottomUnitTable.selectedUnitIsSwapping) {
            for (tileView in unitView.getUnitSwappableTiles())
                tileView.addOverlay(TileOverlay.SWAP_TARGET)
            // In swapping-mode we don't want to show other overlays
            return
        }

        // Z-Layer: 0
        // Highlight suitable tiles in road connecting mode
        if (worldScreen.bottomUnitTable.selectedUnitIsConnectingRoad) {
            if (!unitView.rulesetHasRoadImprovement()) return
            for (tileView in unitView.getValidRoadConnectionTiles())
                tileView.addOverlay(TileOverlay.ROAD_CONNECT_VALID)

            if (unitConnectRoadPaths.containsKey(unitView)) {
                for (tileView in unitConnectRoadPaths[unitView]!!)
                    tileView.addOverlay(TileOverlay.ROAD_CONNECT_PATH)
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
                    tileView.addOverlay(TileOverlay.AIR_NUKE_BLAST)
                } else if (tileView.aerialDistanceTo(unitView.getTile()) <= unitView.getRange()) {
                    // The tile is within attack range
                    tileView.addOverlay(TileOverlay.AIR_ATTACK_RANGE)
                } else if (unitView.isExplored(tileView) && tileView.aerialDistanceTo(unitView.getTile()) <= unitView.getRange()*2) {
                    // The tile is within move range
                    tileView.addOverlay(if (unitView.canMoveTo(tileView)) TileOverlay.AIR_MOVE_RANGE_OK else TileOverlay.AIR_MOVE_RANGE_BLOCKED)
                }
            }

            // Highlight tile unit can move to
            if (unitView.canMoveTo(tileView) ||
                unitView.isUnknownTileWeShouldAssumeToBePassable(tileView) && !isAirUnit
            ) {
                tileView.addOverlay(TileOverlay.MOVABLE_TO)
                if (unitView.isPreparingParadrop()) tileView.addOverlay(TileOverlay.MOVABLE_TO_PARADROP)
            }
        }

        // Z-Layer: 2
        // Add back in the red overlay for Air Unit Attack range since they can't move, but can still attack
        if (unitView.cannotMove() && isAirUnit && !unitView.isPreparingAirSweep()) {
            for (tileView in unitView.getTilesInAttackRange()) {
                // The tile is within attack range
                tileView.addOverlay(TileOverlay.AIR_ATTACK_ONLY)
            }
        }

        // Z-Layer: 3
        // Movement paths
        if (unitMovementPaths.containsKey(unitView)) {
            for (tileView in unitMovementPaths[unitView]!!) {
                tileView.addOverlay(TileOverlay.MOVEMENT_PATH)
            }
        }

        // Z-Layer: 4
        // Highlight road path for workers currently connecting roads
        if (unitView.isAutomatingRoadConnection()) {
            val futureTiles = unitView.getFutureAutomatedRoadConnectionTiles() ?: return
            for (tileView in futureTiles) {
                tileView.addOverlay(TileOverlay.ROAD_AUTOMATION_FUTURE)
            }
        }

        // Z-Layer: 5
        // Highlight movement destination tile
        if (unitView.isMoving()) {
            unitView.getMovementDestination().addOverlay(TileOverlay.MOVEMENT_DESTINATION)
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
                tileViewToAttack.addOverlay(TileOverlay.ATTACKABLE)
                // the targets which cannot be attacked without movements shown as orange-ish
                if (attackableTile.tileToAttackFrom != unit.currentTile)
                    tileViewToAttack.addOverlay(TileOverlay.ATTACKABLE_NEEDS_MOVE)
                if (attackableTile.tileToAttack == selectedTile?.getTile())
                    tileMapView.getTile(attackableTile.tileToAttackFrom).addOverlay(TileOverlay.ATTACK_SOURCE)
            }
        }

        // Z-Layer: 7
        // Highlight best tiles for city founding
        if (unitView.hasUnique(UniqueType.FoundCity)
            && UncivGame.Current.settings.showSettlersSuggestedCityLocations) {
            val unit = unitView.getUnit()
            CityLocationTileRanker.getBestTilesToFoundCity(unit, 5, minimumValue = 50f).tileRankMap.asSequence()
                .filter { it.key.isExplored(unit.civ) }.sortedByDescending { it.value }.take(3).forEach {
                    tileMapView.getTile(it.key).addOverlay(TileOverlay.SUGGESTED_CITY_SITE)
                }
        }
    }

    private fun WorldMapHolder.updateTilesForSelectedSpy(spy: Spy) {
        for (tile in tileMap.tileList) {
            val tileView = tileMapView.getTile(tile)
            // Every tile's own highlight/crosshair/good-city-location-indicator is already reset by
            // resetOverlays() (nothing here sets any of those overlays), matching the old
            // layerOverlay.reset() call this replaces.
            if (!tile.isCityCenter())
                tileView.addOverlay(TileOverlay.DIM_IMPROVEMENT)
            tileView.addOverlay(TileOverlay.SPY_DIM_MODE)
        }
        for (city in worldScreen.gameInfo.getCities()) {
            if (spy.canMoveTo(city)) {
                tileMapView.getTile(city.getCenterTile()).addOverlay(TileOverlay.SPY_TARGET_CITY)
            }
        }
    }

    private fun WorldMapHolder.updateBombardableTilesForSelectedCity(city: City) {
        if (!city.canBombard()) return
        for (tile in TargetHelper.getBombardableTiles(city)) {
            tileMapView.getTile(tile).addOverlay(TileOverlay.BOMBARDABLE)
        }
    }
}
