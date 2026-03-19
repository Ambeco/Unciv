package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.Constants
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.ImprovementFunctions
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ImprovementBuildingProblem
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Counter
import com.unciv.models.UncivSound
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.Conditionals
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.removeConditionals
import com.unciv.models.translations.tr
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.pickerscreens.ImprovementPickerScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers.getUseFrequency
import yairm210.purity.annotations.Readonly

@Suppress("UNUSED_PARAMETER") // These methods are used as references in UnitActions.actionTypeToFunctions and need identical signature
object UnitActionsFromUniques {

    internal fun getFoundCityActions(unit: MapUnit) = sequenceOf(getFoundCityAction(unit)).filterNotNull()

    /** Produce a [UnitAction] for founding a city.
     * @param unit The unit to do the founding.
     * @return null if impossible (the unit lacks the ability to found),
     * or else a [UnitAction] 'defining' the founding.
     * The [action][UnitAction.action] field will be null if the action cannot be done here and now
     * (no movement left, too close to another city).
     */
    internal fun getFoundCityAction(unit: MapUnit): UnitAction? {
        // FoundPuppetCity is to found a puppet city for modding.
        val unique = UnitActionModifiers.getUsableUnitActionUniques(unit,
            UniqueType.FoundCity).firstOrNull() ?: 
            UnitActionModifiers.getUsableUnitActionUniques(unit,
            UniqueType.FoundPuppetCity).firstOrNull() ?: return null

        // Spain should still be able to build Conquistadors in a one city challenge - but can't settle them
        if (unit.civ.isOneCityChallenger() && unit.civ.hasEverOwnedOriginalCapital) return null
        val useFrequency = getUseFrequency(unit, unique, 80f)

        val hasActionModifiers = unique.modifiers.any { it.type?.targetTypes?.contains(
            UniqueTarget.UnitActionModifier
        ) == true }
        val foundAction = {
            val tile = unit.currentTile
            if (unit.civ.playerType != PlayerType.AI)
                UncivGame.Current.settings.addCompletedTutorialTask("Found city")
            // Get the city to be able to change it into puppet, for modding.
            val city = unit.civ.addCity(tile.position, unit)

            if (hasActionModifiers) UnitActionModifiers.activateSideEffects(unit, unique)
            else unit.destroy()
            GUI.setUpdateWorldOnNextRender() // Set manually, since this could be triggered from the ConfirmPopup and not from the UnitActionsTable
            // If unit has FoundPuppetCity make it into a puppet city.
            if (unique.type == UniqueType.FoundPuppetCity) {
                city.isPuppet = true
            }
        }

        if (unit.civ.playerType == PlayerType.AI)
            return UnitAction(unit, UnitActionType.FoundCity, useFrequency, action = foundAction)

        val title =
            if (hasActionModifiers) UnitActionModifiers.actionTextWithSideEffects(
                UnitActionType.FoundCity.value,
                unique,
                unit
            )
            else UnitActionType.FoundCity.value

        return UnitAction(unit, 
            type = UnitActionType.FoundCity,
            useFrequency = useFrequency,
            title = { title },
            uncivSound = UncivSound.Chimes,
            associatedUnique = unique,
            action = {
                val tile = unit.currentTile
                // check if we would be breaking a promise
                val leadersPromisedNotToSettleNear = getLeadersWePromisedNotToSettleNear(unit.civ, tile)
                if (leadersPromisedNotToSettleNear == null)
                    foundAction()
                else {
                    // ask if we would be breaking a promise
                    val text = "Do you want to break your promise to [$leadersPromisedNotToSettleNear]?"
                    ConfirmPopup(
                        GUI.getWorldScreen(),
                        text,
                        "Break promise",
                        action = foundAction
                    ).open(force = true)
                }
            },
            availableResources = { unit.hasMovement() && UnitActionModifiers.canActivateSideEffects(unit, unique) },
            availableOnTile = { tile -> !tile.isWater && !tile.isImpassible() && tile.canBeSettled(unit.civ) },
        )
    }

    /**
     * Checks whether a civ founding a city on a certain tile would break a promise.
     * @param civInfo The civilization trying to found a city
     * @return null if no promises broken, else a String listing the leader(s) we would p* off.
     */
    @Readonly
    private fun getLeadersWePromisedNotToSettleNear(civInfo: Civilization, tile: Tile): String? {
        val leadersWePromisedNotToSettleNear = HashSet<String>()
        for (otherCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() && !civInfo.isAtWarWith(it) }) {
            val diplomacyManager = otherCiv.getDiplomacyManager(civInfo)!!
            if (diplomacyManager.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs)) {
                val citiesWithin6Tiles = otherCiv.cities
                    .filter { it.getCenterTile().aerialDistanceTo(tile) <= 6 }
                    .filter { otherCiv.hasExplored(it.getCenterTile()) }
                if (citiesWithin6Tiles.isNotEmpty()) leadersWePromisedNotToSettleNear += otherCiv.getLeaderDisplayName()
            }
        }
        return if(leadersWePromisedNotToSettleNear.isEmpty()) null else leadersWePromisedNotToSettleNear.joinToString(", ")
    }

    internal fun getSetupActions(unit: MapUnit): Sequence<UnitAction> {
        if (!unit.hasUnique(UniqueType.MustSetUp) || unit.isEmbarked()) return emptySequence()
        val isSetUp = unit.isSetUpForSiege()
        return sequenceOf(UnitAction(unit, UnitActionType.SetUp,
            isCurrentAction = isSetUp,
            useFrequency = 85f,
            action = {
                unit.action = UnitActionType.SetUp.value
                unit.useMovementPoints(1f)
            },
            availableResources = { unit.hasMovement() && !isSetUp })
        )
    }

    internal fun getParadropActions(unit: MapUnit): Sequence<UnitAction> {
        unit.cache.paradropDestinationTileFilters.clear()

        // Retrieve all parardrop uniques, considering the state of the unit
        val paradropUniques = unit.getMatchingUniques(UniqueType.MayParadrop, unit.cache.state)
        var useFrequency = 0f

        // Construct the list of possible destination tile filters, keeping the largest distance
        for (unique in paradropUniques) {
            val tileFilter = unique.params[0]
            val distance = unique.params[1].toInt()
            val existingDistance = unit.cache.paradropDestinationTileFilters[tileFilter]
            if (existingDistance == null || distance > existingDistance) {
                unit.cache.paradropDestinationTileFilters[tileFilter] = distance
                useFrequency = getUseFrequency(unit, unique, 60f)
            }
        }
        if (unit.cache.paradropDestinationTileFilters.isEmpty()) return emptySequence()

        return sequenceOf(UnitAction(unit, UnitActionType.Paradrop,
            isCurrentAction = unit.isPreparingParadrop(),
            useFrequency = useFrequency, // While it is important to see, it isn't nessesary used a lot
            action = {
                if (unit.isPreparingParadrop()) unit.action = null
                else unit.action = UnitActionType.Paradrop.value
            },
            availableResources = { 
                !unit.hasUnitMovedThisTurn()
            })
        )
    }

    internal fun getAirSweepActions(unit: MapUnit): Sequence<UnitAction> {
        val airsweepUnique =
            unit.getMatchingUniquesIgnoringConditionals(UniqueType.CanAirsweep).firstOrNull() ?: return emptySequence()
        val tileDependent = airsweepUnique.conditionalsTileDependent()
        if (!tileDependent && !airsweepUnique.conditionalsApply(unit.cache.state)) return emptySequence()
        val useFrequency = getUseFrequency(unit, airsweepUnique, 90f)
        return sequenceOf(UnitAction(unit, UnitActionType.AirSweep,
            isCurrentAction = unit.isPreparingAirSweep(),
            useFrequency = useFrequency,
            action = {
                if (unit.isPreparingAirSweep()) unit.action = null
                else unit.action = UnitActionType.AirSweep.value
            },
            availableResources = {
                unit.canAttack()
            },
            availableOnTile
                    = if (tileDependent) { { airsweepUnique.conditionalsApply(unit.cache.state.copy(tile=it)) } }
                    else null
        ))
    }

    // Instead of Withdrawing, stand your ground!
    // Different than Fortify
    internal fun getGuardActions(unit: MapUnit): Sequence<UnitAction> {
        val unique = unit.getMatchingUniques(UniqueType.WithdrawsBeforeMeleeCombat).firstOrNull() ?: return emptySequence()
        val useFrequency = getUseFrequency(unit, unique, 0f)

        if (unit.isGuarding()) {
            val title = if (unit.canFortify()) "${"Guarding".tr()} ${unit.getFortificationTurns() * 20}%" else "Guarding".tr()
            return sequenceOf(UnitAction(unit, UnitActionType.Guard,
                useFrequency = useFrequency,
                isCurrentAction = true,
                title = { title },
                action = {},
                availableResources = { false }
            ))
        }
        
        if (!unit.hasMovement()) return emptySequence()
        
        return sequenceOf(UnitAction(unit, UnitActionType.Guard,
            useFrequency = useFrequency,
            action = {
                unit.action = UnitActionType.Guard.value
            },
            availableResources = { !unit.isGuarding() })
        )
    }

    internal fun getTriggerUniqueActions(unit: MapUnit) = sequence {
        for (unique in unit.getUniques()) {
            // not a unit action
            if (unique.modifiers.none { it.type?.targetTypes?.contains(UniqueTarget.UnitActionModifier) == true }) continue
            // extends an existing unit action
            if (unique.hasModifier(UniqueType.UnitActionExtraLimitedTimes)) continue
            if (!unique.isTriggerable) continue
            val uniqueIsTileDependent = unique.conditionalsTileDependent()
            if (!uniqueIsTileDependent && !unique.conditionalsApply(unit.cache.state)) continue
            if (!uniqueIsTileDependent && !UnitActionModifiers.canUse(unit, unique)) continue

            val baseTitle = when (unique.type) {
                UniqueType.OneTimeEnterGoldenAgeTurns -> {
                    unique.placeholderText.fillPlaceholders(
                        unit.civ.goldenAges.calculateGoldenAgeLength(
                            unique.params[0].toInt()).tr())
                    }
                UniqueType.OneTimeGainStat -> {
                    if (unique.hasModifier(UniqueType.ModifiedByGameSpeed)) {
                        val stat = unique.params[1]
                        val modifier = unit.civ.gameInfo.speed.statCostModifiers[Stat.safeValueOf(stat)]
                            ?: unit.civ.gameInfo.speed.modifier
                        UniqueType.OneTimeGainStat.placeholderText.fillPlaceholders(
                            (unique.params[0].toInt() * modifier).toInt().tr(), stat
                        )
                    }
                    else unique.text.removeConditionals()
                }
                UniqueType.OneTimeGainStatRange -> {
                    val stat = unique.params[2]
                    val modifier = unit.civ.gameInfo.speed.statCostModifiers[Stat.safeValueOf(stat)]
                        ?: unit.civ.gameInfo.speed.modifier
                    unique.placeholderText.fillPlaceholders(
                        (unique.params[0].toInt() * modifier).toInt().tr(),
                        (unique.params[1].toInt() * modifier).toInt().tr(),
                        stat
                    )
                }
                UniqueType.TriggerEvent -> unique.params[0]
                else -> unique.text.removeConditionals()
            }
            val title = UnitActionModifiers.actionTextWithSideEffects(baseTitle, unique, unit)
            val useFrequency = getUseFrequency(unit, unique, 80f)

            val unitAction = {
                val triggerFunction = UniqueTriggerActivation.getTriggerFunction(unique,  unit.civ, unit = unit, tile = unit.currentTile)
                if (triggerFunction != null) {
                    repeat(unique.getUniqueMultiplier(unit.cache.state)) {
                        triggerFunction.invoke()
                    }
                    UnitActionModifiers.activateSideEffects(unit, unique)
                }
            }
            val availableOnTile =
                if (uniqueIsTileDependent)
                    {tile: Tile -> unique.conditionalsApply(unit.cache.state.copy(tile = tile)) }
                else null

            yield(
                UnitAction(unit, UnitActionType.TriggerUnique, useFrequency, { title },
                    associatedUnique = unique,
                    action = unitAction,
                    availableResources = {
                        unit.hasMovement()
                            && UnitActionModifiers.canUse(unit, unique)
                            && UnitActionModifiers.canActivateSideEffects(unit, unique)
                    },
                    availableOnTile = availableOnTile
                )
            )
        }
    }

    internal fun getAddInCapitalActions(unit: MapUnit): Sequence<UnitAction> {
        val unique = unit.getMatchingUniquesIgnoringConditionals(UniqueType.AddInCapital, checkCivInfoUniques = true)
            .firstOrNull() ?: return emptySequence()
        val useFrequency = getUseFrequency(unit, unique, 80f)
        return sequenceOf(UnitAction(unit, UnitActionType.AddInCapital,
            title = {"Add to [${unique.params[0]}]"},
            useFrequency = useFrequency,
            action = {
                unit.civ.victoryManager.currentsSpaceshipParts.add(unit.name, 1)
                unit.destroy()
            },
            
            availableOnTile = { tile ->
                tile.isCityCenter() && tile.getCity()!!.isCapital() && tile.getCity()!!.civ == unit.civ
                    && unique.conditionalsApply(unit.cache.state.copy(tile = tile))
            },
        ))
    }

    internal fun getImprovementCreationActions(unit: MapUnit) = sequence {
        val waterImprovementAction = getWaterImprovementAction(unit)
        if (waterImprovementAction != null) yield(waterImprovementAction)
        yieldAll(getImprovementConstructionActionsFromGeneralUnique(unit, unit.currentTile))
    }

    private fun getWaterImprovementAction(unit: MapUnit): UnitAction? {
        val unique = unit.getMatchingUniquesIgnoringConditionals(UniqueType.CreateWaterImprovements).firstOrNull()
        if (unique == null) return null
        
        fun tileAvailable(tile: Tile): Boolean {
            if (!tile.isWater) return@tileAvailable false

            val improvement = tile.tileResource?.getImprovingImprovement(tile, unit.cache.state) ?: return@tileAvailable false
            return@tileAvailable tile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state)
                && unique.conditionalsApply(unit.cache.state.copy(tile = tile))
        }

        val useFrequency = getUseFrequency(unit, unique, 82f)
        val title = {tile: Tile -> 
            val improvementName = tile.tileResource?.getImprovingImprovement(tile, unit.cache.state)
            "Create [$improvementName]"
        }

        return UnitAction(unit, UnitActionType.CreateImprovement, useFrequency, title,
            action = {
                val tile = unit.currentTile
                val improvement = tile.tileResource?.getImprovingImprovement(tile, unit.cache.state)!!
                tile.setImprovement(improvement, unit.civ, unit)
                unit.destroy()  // Modders may wish for a nondestructive way, but that should be another Unique
            },
            availableResources = { unit.hasMovement() },
            availableOnTile = { tile -> tileAvailable(tile) })
    }

    // Not internal: Used in SpecificUnitAutomation
    fun getImprovementConstructionActionsFromGeneralUnique(unit: MapUnit, tile: Tile) = sequence {
        val uniquesToCheck = UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.ConstructImprovementInstantly)

        val civResources = unit.civ.getCivResourcesByName()
        val gameContext = GameContext(civInfo = unit.civ, unit = unit, tile = tile)

        for (unique in uniquesToCheck) {
            val improvementFilter = unique.params[0]
            val improvements = tile.ruleset.tileImprovements.values.filter { it.matchesFilter(improvementFilter, gameContext) }
            val useFrequency = getUseFrequency(unit, unique, 85f)

            for (improvement in improvements) {
                // Try to skip Improvements we can never build
                // (getImprovementBuildingProblems catches those so the button is always disabled, but it nevertheless looks nicer)
                if (tile.improvementFunctions.getImprovementBuildingProblems(improvement, gameContext).any { it.permanent })
                    continue

                fun resourcesAvailable() = improvement.getMatchingUniques(UniqueType.ConsumesResources).none { improvementUnique ->
                        (civResources[improvementUnique.params[1]] ?: 0) < improvementUnique.params[0].toInt()
                }

                val isTileDependent = unique.conditionalsTileDependent()
                yield(UnitAction(unit, UnitActionType.CreateImprovement, useFrequency,
                    title = {UnitActionModifiers.actionTextWithSideEffects(
                        "Create [${improvement.name}]",
                        unique,
                        unit
                    )},
                    associatedUnique = unique,
                    action = {
                        val unitTile = unit.getTile()
                        unitTile.setImprovement(improvement, unit.civ, unit)

                        unit.civ.cache.updateViewableTiles() // to update 'last seen improvement'

                        UnitActionModifiers.activateSideEffects(unit, unique)
                    },
                    availableResources = {
                        resourcesAvailable()
                            && unit.hasMovement()
                            && UnitActionModifiers.canActivateSideEffects(unit, unique)
                    },
                    availableOnTile = { tile ->
                            tile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state)
                            // Next test is to prevent interfering with UniqueType.CreatesOneImprovement -
                            // not pretty, but users *can* remove the building from the city queue an thus clear this:
                            && !tile.isMarkedForCreatesOneImprovement()
                            &&  (if (isTileDependent) UnitActionModifiers.canActivateSideEffects(unit, unique) else true)
                    },
                ))
            }
        }
    }

    internal fun getConnectRoadActions(unit: MapUnit) = sequence {
        if (!unit.hasUnique(UniqueType.BuildImprovements)) return@sequence
        val unitCivBestRoad = unit.civ.tech.getBestRoadAvailable()
        if (unitCivBestRoad == RoadStatus.None) return@sequence

        val uniquesToCheck = UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.BuildImprovements)

        // If a unit has terrainFilter "Land" or improvementFilter "All", then we may proceed.
        // If a unit only had improvement filter "Road" or "Railroad", then we need to also check if that tech is unlocked
        val unique = uniquesToCheck.firstOrNull { it.params[0] == "Land" || it.params[0] in Constants.all
                || (it.params[0] == "Road" && (unitCivBestRoad == RoadStatus.Road || unitCivBestRoad == RoadStatus.Railroad))
                || (it.params[0] == "Railroad" && (unitCivBestRoad == RoadStatus.Railroad))
        }

        if(unique == null) return@sequence
        val useFrequency = getUseFrequency(unit, unique, 25f)

        yield(UnitAction(unit, UnitActionType.ConnectRoad, useFrequency, // Press once for a multiturn command, it doesn't need to be used that frequently
               isCurrentAction = unit.isAutomatingRoadConnection(),
               action = {
                   val worldScreen = GUI.getWorldScreen()
                   worldScreen.bottomUnitTable.selectedUnitIsConnectingRoad =
                       !worldScreen.bottomUnitTable.selectedUnitIsConnectingRoad
                   worldScreen.shouldUpdate = true
               }
           )
        )
    }

    internal fun getTransformActions(unit: MapUnit) = sequence {
        val unitTile = unit.getTile()
        val civInfo = unit.civ
        val stateForConditionals = unit.cache.state

        for (unique in unit.getMatchingUniquesIgnoringConditionals(UniqueType.CanTransform)) {
            val unitToTransformTo = civInfo.getEquivalentUnit(unique.params[0])

            val onlyAvailableCriteria = unitToTransformTo.getMatchingUniques(
                UniqueType.OnlyAvailable, GameContext.IgnoreConditionals
            )
            val tileDependant = unique.conditionalsTileDependent() || onlyAvailableCriteria.any { it.conditionalsTileDependent() }
            // Respect OnlyAvailable criteria
            if (!tileDependant && onlyAvailableCriteria.any { !it.conditionalsApply(stateForConditionals) })
                continue

            // Respect Unavailable criteria
            if (unitToTransformTo.getMatchingUniques(UniqueType.Unavailable, stateForConditionals).any())
                continue

            // Check _new_ resource requirements
            // Using Counter to aggregate is a bit exaggerated, but - respect the mad modder.
            val resourceRequirementsDelta = Counter<String>()
            for ((resource, amount) in unit.getResourceRequirementsPerTurn())
                resourceRequirementsDelta.add(resource, -amount)
            for ((resource, amount) in unitToTransformTo.getResourceRequirementsPerTurn(unit.cache.state))
                resourceRequirementsDelta.add(resource, amount)
            val newResourceRequirementsString = resourceRequirementsDelta.entries
                .filter { it.value > 0 }
                .joinToString { "${it.value} {${it.key}}".tr() }

            var title = "Transform to [${unitToTransformTo.name}] "
            title += UnitActionModifiers.getSideEffectString(unit, unique, true)
            if (newResourceRequirementsString.isNotEmpty())
                title += "\n([$newResourceRequirementsString])"
            val useFrequency = getUseFrequency(unit, unique, 70f)

            yield(UnitAction(unit, UnitActionType.Transform, useFrequency,
                title = {title},
                associatedUnique = unique,
                action = {
                    val oldMovement = unit.currentMovement
                    unit.destroy()
                    val newUnit =
                        civInfo.units.placeUnitNearTile(unitTile.position, unitToTransformTo, unit.id, copiedFrom = unit)

                    /** We were UNABLE to place the new unit, which means that the unit failed to upgrade!
                     * The only known cause of this currently is "land units upgrading to water units" which fail to be placed.
                     */
                    if (newUnit == null) {
                        val resurrectedUnit =
                            civInfo.units.placeUnitNearTile(unitTile.position, unit.baseUnit, unit.id, copiedFrom = unit)!!
                        
                    } else { // Managed to upgrade
                        // have to handle movement manually because we killed the old unit
                        // a .destroy() unit has 0 movement
                        // and a new one may have less Max Movement
                        newUnit.currentMovement = oldMovement
                        // adjust if newUnit has lower Max Movement
                        if (newUnit.currentMovement.toInt() > newUnit.getMaxMovement())
                            newUnit.currentMovement = newUnit.getMaxMovement().toFloat()
                        // execute any side effects, Stat and Movement adjustments
                        UnitActionModifiers.activateSideEffects(newUnit, unique, true)
                    }
                },
                availableResources = {
                    !unit.isEmbarked() && UnitActionModifiers.canActivateSideEffects(unit, unique)
                 },
                availableOnTile 
                    = if (tileDependant) {{ tile -> 
                        unique.conditionalsApply(unit.cache.state.copy(tile = tile))
                        && onlyAvailableCriteria.all { it.conditionalsApply(stateForConditionals.copy(tile = tile)) }
                    }}
                    else null,
            ))
        }
    }

    internal fun getBuildingImprovementsActions(unit: MapUnit): Sequence<UnitAction> {
        if (!unit.cache.hasUniqueToBuildImprovements) return emptySequence()
        val unique = unit.getMatchingUniquesIgnoringConditionals(UniqueType.BuildImprovements).first()

        val couldConstruct = { tile: Tile ->
                !tile.isCityCenter()
                && unique.conditionalsApply(unit.cache.state.copy(tile = tile))
                && unit.civ.gameInfo.ruleset.tileImprovements.values.any {
                ImprovementPickerScreen.canReport(
                    tile.improvementFunctions.getImprovementBuildingProblems(
                        it,
                        unit.cache.state
                    ).toSet()
                )
                    && unit.canBuildImprovement(it, tile)
            }
        }
        val useFrequency = getUseFrequency(unit, unique, 85f)

        return sequenceOf(UnitAction(unit, UnitActionType.ConstructImprovement, useFrequency,
            isCurrentAction = unit.currentTile.hasImprovementInProgress(),
            action = { 
                val tile = unit.currentTile
                GUI.pushScreen(ImprovementPickerScreen(tile, unit) {
                    if (GUI.getSettings().autoUnitCycle)
                        GUI.getWorldScreen().switchToNextUnit()
                })
            },
            availableResources = { unit.hasMovement() },
            availableOnTile = couldConstruct,
        ))
    }

    @Readonly
    internal fun getRepairTurns(unit: MapUnit, tile: Tile): Int {
        if (!tile.isPillaged()) return 0
        if (tile.improvementInProgress == Constants.repair) return tile.turnsToImprovement
        val repairTurns = tile.ruleset.tileImprovements[Constants.repair]!!.getTurnsToBuild(unit.civ, unit)

        val pillagedImprovement = tile.getImprovementToRepair()!!
        val turnsToBuild = pillagedImprovement.getTurnsToBuild(unit.civ, unit)
        // cap repair to number of turns to build original improvement
        return repairTurns.coerceAtMost(turnsToBuild)
    }

    internal fun getRepairActions(unit: MapUnit) =
        sequenceOf(getRepairAction(unit)).filterNotNull()

    // Public - used in WorkerAutomation
    fun getRepairAction(unit: MapUnit) : UnitAction? {
        if (!unit.cache.hasUniqueToBuildImprovements) return null
        if (unit.isEmbarked()) return null
        val tile = unit.getTile()
        if (tile.isCityCenter()) return null
        if (!tile.isPillaged()) return null
        val unique = unit.getMatchingUniquesIgnoringConditionals(UniqueType.BuildImprovements).first()

        val couldConstruct = {tile: Tile ->
            unique.conditionalsApply(unit.cache.state.copy(tile = tile)) 
            && unit.hasMovement()
            && !tile.isCityCenter() && tile.improvementInProgress != Constants.repair
            && !tile.isEnemyTerritory(unit.civ)
                // Are there any other improvement building problems that should block repair?
            && ImprovementFunctions.getImprovementBuildingProblems(tile.getImprovementToRepair()!!, GameContext(civInfo = unit.civ, unit = unit, tile = tile))
                .none { it == ImprovementBuildingProblem.OutsideBorders }
        }
        val useFrequency = getUseFrequency(unit, unique, 90f)

        return UnitAction(unit, UnitActionType.Repair, useFrequency,
            title = {tile -> "${UnitActionType.Repair} [${tile.getImprovementToRepair()!!.name}] - [${getRepairTurns(unit, tile)}${Fonts.turn}]"},
            action = {
                tile.queueImprovement(Constants.repair, getRepairTurns(unit, tile))
                unit.action = null
            },
            availableResources = { unit.hasMovement() },
            availableOnTile = couldConstruct,
        )
    }
}
