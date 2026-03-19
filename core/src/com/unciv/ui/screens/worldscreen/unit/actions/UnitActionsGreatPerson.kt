package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.city.CityConstructions
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.Conditionals
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers.getUseFrequency
import kotlin.math.min

@Suppress("UNUSED_PARAMETER") // references need to have the signature expected by UnitActions.actionTypeToFunctions
object UnitActionsGreatPerson {

    internal fun getHurryResearchActions(unit: MapUnit) = sequence {
        for (unique in unit.getMatchingUniquesIgnoringConditionals(UniqueType.CanHurryResearch)){
            val isTileDependent = unique.conditionalsTileDependent()
            if (!isTileDependent && !unique.conditionalsApply(unit.cache.state)) continue
            val useFrequency = getUseFrequency(unit, unique, 76f)
            yield(UnitAction(unit, 
                UnitActionType.HurryResearch, useFrequency,
                action = {
                    unit.civ.tech.addScience(unit.civ.tech.getScienceFromGreatScientist())
                    unit.consume()
                },
                availableResources = { unit.hasMovement() && unit.civ.tech.currentTechnologyName() != null
                    && !unit.civ.tech.currentTechnology()!!.hasUnique(UniqueType.CannotBeHurried)},
                availableOnTile = if (isTileDependent) { { tile -> unique.conditionalsApply(unit.cache.state.copy(tile = tile)) } } else null
            ))
        }
    }

    internal fun getHurryPolicyActions(unit: MapUnit) = sequence {
        for (unique in unit.getMatchingUniquesIgnoringConditionals(UniqueType.CanHurryPolicy)){
            val isTileDependent = unique.conditionalsTileDependent()
            if (!isTileDependent && !unique.conditionalsApply(unit.cache.state)) continue
            val useFrequency = getUseFrequency(unit, unique, 76f)
            yield(UnitAction(unit, 
                UnitActionType.HurryPolicy, useFrequency,
                action = {
                    unit.civ.policies.addCulture(unit.civ.policies.getCultureFromGreatWriter())
                    unit.consume()
                },
                availableResources = {unit.hasMovement()},
                availableOnTile = if (isTileDependent) { { tile -> unique.conditionalsApply(unit.cache.state.copy(tile = tile)) } } else null
            ))
        }
    }

    internal fun getHurryWonderActions(unit: MapUnit) = sequence {
        for (unique in unit.getMatchingUniquesIgnoringConditionals(UniqueType.CanSpeedupWonderConstruction)) {
            val tileDependant = unique.conditionalsTileDependent()
            if (!tileDependant && !unique.conditionalsApply(unit.cache.state))
                continue
            val useFrequency = getUseFrequency(unit, unique, 75f)

            yield(UnitAction(unit, 
                UnitActionType.HurryWonder, useFrequency,
                action = { 
                    val tile = unit.currentTile
                    tile.getCity()!!.cityConstructions.apply {
                        //http://civilization.wikia.com/wiki/Great_engineer_(Civ5)
                        addProductionPoints(((300 + 30 * tile.getCity()!!.population.population) * unit.civ.gameInfo.speed.productionCostModifier).toInt())
                        constructIfEnough()
                    }

                    unit.consume()
                },
                availableResources = { unit.hasMovement() },
                availableOnTile = { tile ->
                    val canHurryWonder =
                        if (!tile.isCityCenter()) false
                        else tile.getCity()!!.cityConstructions.isBuildingWonder()
                            && tile.getCity()!!.cityConstructions.canBeHurried()
                    unit.hasMovement() && canHurryWonder && unique.conditionalsApply(unit.cache.state.copy(tile = tile))
                },
            ))
        }
    }

    internal fun getHurryBuildingActions(unit: MapUnit) = sequence {
        //http://civilization.wikia.com/wiki/Great_engineer_(Civ5)
        fun productionPointsToAdd(tile: Tile, cityConstructions: CityConstructions) = min(
            (300 + 30 * tile.getCity()!!.population.population) * unit.civ.gameInfo.speed.productionCostModifier,
            cityConstructions.getRemainingWork(cityConstructions.currentConstructionName())
                .toFloat() - 1
            ).toInt()
        for (unique in unit.getMatchingUniquesIgnoringConditionals(UniqueType.CanSpeedupConstruction)) {
            val availableOnTile = available@{tile: Tile ->
                if (!tile.isCityCenter())
                    return@available false

                val cityConstructions = tile.getCity()!!.cityConstructions
                val canHurryConstruction = cityConstructions.getCurrentConstruction() is Building
                    && cityConstructions.canBeHurried()
                if (!unique.conditionalsApply(unit.cache.state.copy(tile = tile))) return@available false
                if (productionPointsToAdd(tile, cityConstructions) <= 0) return@available false
                return@available canHurryConstruction
            }
            val useFrequency = getUseFrequency(unit, unique, 75f)
            yield(UnitAction(
                unit,
                UnitActionType.HurryBuilding, useFrequency,
                title = { tile ->
                    val cityConstructions = tile.getCity()!!.cityConstructions
                    "Hurry Construction (+[${productionPointsToAdd(tile, cityConstructions)}]⚙)"
                },
                action = {
                    val tile = unit.currentTile
                    val cityConstructions = tile.getCity()!!.cityConstructions
                    cityConstructions.apply {
                        addProductionPoints(productionPointsToAdd(tile, cityConstructions))
                        constructIfEnough()
                    }

                    unit.consume()
                },
                availableResources = { unit.hasMovement() },
                availableOnTile = availableOnTile,
            ))
        }
    }

    internal fun getConductTradeMissionActions(unit: MapUnit) = sequence {
        fun canConductTradeMission(tile: Tile) = tile.owningCity?.civ?.isCityState == true
            && tile.owningCity?.civ != unit.civ
            && tile.owningCity?.civ?.isAtWarWith(unit.civ) == false
        for (unique in unit.getMatchingUniquesIgnoringConditionals(UniqueType.CanTradeWithCityStateForGoldAndInfluence)) {
            val influenceEarned = unique.params[0].toFloat()
            val useFrequency = getUseFrequency(unit, unique, 70f)

            yield(UnitAction(unit, 
                UnitActionType.ConductTradeMission, useFrequency,
                action = {
                    val tile = unit.currentTile
                    // http://civilization.wikia.com/wiki/Great_Merchant_(Civ5)
                    var goldEarned = (350 + 50 * unit.civ.getEraNumber()) * unit.civ.gameInfo.speed.goldCostModifier

                    // Apply the gold trade mission modifier
                    for (goldUnique in unit.getMatchingUniques(UniqueType.PercentGoldFromTradeMissions, checkCivInfoUniques = true))
                        goldEarned *= goldUnique.params[0].toPercent()

                    val goldEarnedInt = goldEarned.toInt()
                    unit.civ.addGold(goldEarnedInt)
                    val tileOwningCiv = tile.owningCity!!.civ

                    tileOwningCiv.getDiplomacyManager(unit.civ)!!.addInfluence(influenceEarned)
                    unit.civ.addNotification("Your trade mission to [$tileOwningCiv] has earned you [${goldEarnedInt.tr()}] gold and [${influenceEarned.tr()}] influence!",
                        NotificationCategory.General, tileOwningCiv.civName, NotificationIcon.Gold, NotificationIcon.Culture)
                    unit.consume()
                },
                availableResources = { unit.hasMovement() },
                availableOnTile = { tile -> unique.conditionalsApply(unit.cache.state.copy(tile = tile)) && canConductTradeMission(tile) },
            ))
        }
    }
}
