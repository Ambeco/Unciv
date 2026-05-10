package com.unciv.models.ruleset.unique

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.battle.CombatAction
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext.Companion.IgnoreConditionalsFlags
import com.unciv.models.ruleset.validation.ModCompatibility
import com.unciv.models.stats.Stat
import com.unciv.utils.hashOf
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

object Conditionals {

    @Readonly @Suppress("purity") // hashcode... requires a think
    private fun getStateBasedRandom(state: GameContext, unique: Unique?): Float {
        
        val seed = hashOf(state.gameInfo?.turns?.hashCode() ?: 0,
            unique?.hashCode() ?: 0,
            state.hashCode())
        return Random(seed).nextFloat()
    }

    @Readonly
    fun conditionalApplies(
        unique: Unique?,
        conditional: Unique,
        state: GameContext
    ): Boolean = timeThis("Conditionals.conditionalApplies") {

        if (conditional.isOtherModifierType)
            return true // not a filtering condition, includes e.g. ModifierHiddenFromUsers

        /** Helper to simplify conditional tests requiring gameInfo */
        @Readonly
        fun checkOnGameInfo(@Readonly predicate: (GameInfo.() -> Boolean)): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_GAME_INFO)) return true
            if (state.gameInfo == null) return false
            return state.gameInfo.predicate()
        }

        /** Helper to simplify conditional tests requiring a City */
        @Readonly
        fun checkOnRelevantUnit(@Readonly predicate: (MapUnit.() -> Boolean)): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_UNIT)) return true
            if (state.relevantUnit == null) return false
            return state.relevantUnit!!.predicate()
        }

        /** Helper to simplify conditional tests requiring a City */
        @Readonly
        fun checkOurCombatantAction(@Readonly predicate: (ICombatant.() -> Boolean)): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_OUR_COMBATANT)) return true
            if (state.ourCombatant == null) return false
            return state.ourCombatant.predicate()
        }

        /** Helper to simplify conditional tests requiring a Civilization */
        @Readonly
        fun checkOnCiv(@Readonly predicate: (Civilization.() -> Boolean)): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_CIVILIZATION)) return true
            if (state.relevantCiv == null) return false
            return state.relevantCiv!!.predicate()
        }

        /** Helper to simplify conditional tests requiring a City */
        @Readonly
        fun checkOnCity(@Readonly predicate: (City.() -> Boolean)): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_CITY)) return true
            if (state.relevantCity == null) return false
            return state.relevantCity!!.predicate()
        }

        /** Helper to simplify conditional tests requiring a City */
        @Readonly
        fun checkOnCityOrCiv(@Readonly predicate: () -> Boolean): Boolean {
            if (state.relevantCity != null) {
                if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_CITY))
                    return true
            } else if (state.relevantCiv != null) {
                if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_CIVILIZATION))
                    return true
            } 
            return predicate()
        }

        /** Helper to simplify conditional tests requiring a City */
        @Readonly
        fun checkOnTheirCombatant(@Readonly predicate: (ICombatant.() -> Boolean)): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_THEIR_COMBATANT)) return true
            if (state.theirCombatant == null) return false
            return state.theirCombatant.predicate()
        }

        /** Helper to simplify the "compare civ's current era with named era" conditions */
        @Readonly
        fun compareEra(eraParam: String, @Readonly compare: (civEra: Int, paramEra: Int) -> Boolean): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_GAME_INFO)) return true
            if (state.gameInfo == null) return false
            val era = state.gameInfo.ruleset.eras[eraParam] ?: return false
            return compare(state.relevantCiv!!.getEraNumber(), era.eraNumber)
        }

        /** Helper to simplify conditional tests requiring a City */
        @Readonly
        fun checkOnTile(@Readonly predicate: (Tile.() -> Boolean)): Boolean {
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_TILE)) return true
            if (state.relevantTile == null) return false
            return state.relevantTile!!.predicate()
        }

        /** Helper for ConditionalWhenAboveAmountStatResource and its below counterpart */
        @Readonly
        fun checkResourceOrStatAmount(
            resourceOrStatName: String,
            lowerLimit: Float,
            upperLimit: Float,
            modifyByGameSpeed: Boolean = false,
            @Readonly compare: (current: Int, lowerLimit: Float, upperLimit: Float) -> Boolean
        ): Boolean {
            if (state.gameInfo == null) return false
            var gameSpeedModifier = if (modifyByGameSpeed) state.gameInfo.speed.modifier else 1f

            if (state.gameInfo.ruleset.tileResources.containsKey(resourceOrStatName)) {
                if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_TILE)) return true
                return compare(state.getResourceAmount(resourceOrStatName), lowerLimit * gameSpeedModifier, upperLimit * gameSpeedModifier)
            }
            if (state.relevantCity != null && state.ignoreConditionals.contains(
                    IgnoreConditionalsFlags.IGNORE_CITY)) return true
            if (state.ignoreConditionals.contains(IgnoreConditionalsFlags.IGNORE_CIVILIZATION)) return true
            val stat = Stat.safeValueOf(resourceOrStatName)
                ?: return false
            val statReserve = state.getStatAmount(stat)

            gameSpeedModifier = if (modifyByGameSpeed) state.gameInfo.speed.statCostModifiers[stat]!! else 1f
            return compare(statReserve, lowerLimit * gameSpeedModifier, upperLimit * gameSpeedModifier)
        }

        @Readonly
        fun compareCountables(
            first: String,
            second: String,
            @Readonly compare: (first: Int, second: Int) -> Boolean): Boolean {
            if (Countables.shouldIgnore(first, state) || Countables.shouldIgnore(second, state)) return true

            val firstNumber = Countables.getCountableAmount(first, state)
            val secondNumber = Countables.getCountableAmount(second, state)

            return if (firstNumber != null && secondNumber != null)
                compare(firstNumber, secondNumber)
            else
                false
        }

        @Readonly
        fun compareCountables(first: String, second: String, third: String,
                              @Readonly compare: (first: Int, second: Int, third: Int) -> Boolean): Boolean {
            if (Countables.shouldIgnore(first, state) 
                || Countables.shouldIgnore(second, state)
                || Countables.shouldIgnore(third, state))
                return true

            val firstNumber = Countables.getCountableAmount(first, state)
            val secondNumber = Countables.getCountableAmount(second, state)
            val thirdNumber = Countables.getCountableAmount(third, state)

            return if (firstNumber != null && secondNumber != null && thirdNumber != null)
                compare(firstNumber, secondNumber, thirdNumber)
            else
                false
        }

        return when (conditional.type) {
            UniqueType.ConditionalChance -> getStateBasedRandom(state, unique) < conditional.params[0].toFloat() / 100f
            UniqueType.ConditionalEveryTurns -> checkOnGameInfo { turns % conditional.params[0].toInt() == 0 }
            UniqueType.ConditionalBeforeTurns -> checkOnGameInfo { turns < conditional.params[0].toInt() }
            UniqueType.ConditionalAfterTurns -> checkOnGameInfo { turns >= conditional.params[0].toInt() }
            UniqueType.ConditionalTutorialsEnabled -> UncivGame.Current.settings.showTutorials
            UniqueType.ConditionalTutorialCompleted -> conditional.params[0] in UncivGame.Current.settings.tutorialTasksCompleted

            UniqueType.ConditionalCivFilter -> checkOnCiv { matchesFilter(conditional.params[0], state) }
            UniqueType.ConditionalWar -> checkOnCiv { isAtWar() }
            UniqueType.ConditionalNotWar -> checkOnCiv { !isAtWar() }
            UniqueType.ConditionalWithResource -> checkOnCityOrCiv {state.getResourceAmount(conditional.params[0]) > 0 }
            UniqueType.ConditionalWithoutResource -> checkOnCityOrCiv {state.getResourceAmount(conditional.params[0]) <= 0 }

            UniqueType.ConditionalWhenAboveAmountStatResource ->
                checkResourceOrStatAmount(conditional.params[1], conditional.params[0].toFloat(), Float.MAX_VALUE, unique?.isModifiedByGameSpeed() == true)
                    { current, lowerLimit, _ -> current > lowerLimit }
            UniqueType.ConditionalWhenBelowAmountStatResource ->
                checkResourceOrStatAmount(conditional.params[1], Float.MIN_VALUE, conditional.params[0].toFloat(), unique?.isModifiedByGameSpeed() == true)
                    { current, _, upperLimit -> current < upperLimit }
            UniqueType.ConditionalWhenBetweenStatResource ->
                checkResourceOrStatAmount(conditional.params[2], conditional.params[0].toFloat(), conditional.params[1].toFloat(), unique?.isModifiedByGameSpeed() == true)
                    { current, lowerLimit, upperLimit -> current >= lowerLimit && current <= upperLimit }

            UniqueType.ConditionalHappy -> checkOnCiv { stats.happiness >= 0 }
            UniqueType.ConditionalGoldenAge -> checkOnCiv { goldenAges.isGoldenAge() }
            UniqueType.ConditionalNotGoldenAge -> checkOnCiv { !goldenAges.isGoldenAge() }

            UniqueType.ConditionalBeforeEra -> compareEra(conditional.params[0]) { current, param -> current < param }
            UniqueType.ConditionalStartingFromEra -> compareEra(conditional.params[0]) { current, param -> current >= param }
            UniqueType.ConditionalDuringEra -> compareEra(conditional.params[0]) { current, param -> current == param }
            UniqueType.ConditionalIfStartingInEra -> checkOnGameInfo { gameParameters.startingEra == conditional.params[0] }
            UniqueType.ConditionalSpeed -> checkOnGameInfo { gameParameters.speed == conditional.params[0] }
            UniqueType.ConditionalDifficulty -> checkOnGameInfo { gameParameters.difficulty == conditional.params[0] }
            UniqueType.ConditionalDifficultyOrHigher -> checkOnGameInfo {
                val difficulty = conditional.params[0]
                if (difficulty in ruleset.difficulties) {
                    val difficulties = ruleset.difficulties.keys.toList()
                    difficulties.indexOf(getDifficulty().name) >= difficulties.indexOf(difficulty)
                } else false
            }
            UniqueType.ConditionalDifficultyOrLower -> checkOnGameInfo {
                val difficulty = conditional.params[0]
                if (difficulty in ruleset.difficulties) {
                    val difficulties = ruleset.difficulties.keys.toList()
                    difficulties.indexOf(getDifficulty().name) <= difficulties.indexOf(difficulty)
                } else false
            }
            UniqueType.ConditionalVictoryEnabled -> checkOnGameInfo { gameParameters.victoryTypes.contains(conditional.params[0]) }
            UniqueType.ConditionalVictoryDisabled -> checkOnGameInfo { !gameParameters.victoryTypes.contains(conditional.params[0]) }
            UniqueType.ConditionalReligionEnabled -> checkOnGameInfo { isReligionEnabled() }
            UniqueType.ConditionalReligionDisabled -> checkOnGameInfo { !isReligionEnabled() }
            UniqueType.ConditionalEspionageEnabled -> checkOnGameInfo { isEspionageEnabled() }
            UniqueType.ConditionalEspionageDisabled -> checkOnGameInfo { !isEspionageEnabled() }
            UniqueType.ConditionalNuclearWeaponsEnabled -> checkOnGameInfo { gameParameters.nuclearWeaponsEnabled }
            UniqueType.ConditionalNuclearWeaponsDisabled -> checkOnGameInfo { !gameParameters.nuclearWeaponsEnabled }
            UniqueType.ConditionalTech -> checkOnCiv {
                val filter = conditional.params[0]
                if (filter in gameInfo.ruleset.technologies) tech.isResearched(conditional.params[0]) // fast common case
                else tech.researchedTechnologies.any { it.matchesFilter(filter) }
            }
            UniqueType.ConditionalNoTech -> checkOnCiv {
                val filter = conditional.params[0]
                if (filter in gameInfo.ruleset.technologies) !tech.isResearched(conditional.params[0]) // fast common case
                else tech.researchedTechnologies.none { it.matchesFilter(filter) }
            }
            UniqueType.ConditionalWhileResearching -> checkOnCiv { tech.currentTechnology()?.matchesFilter(conditional.params[0]) == true }
            UniqueType.ConditionalNoCivAdopted -> checkOnGameInfo {
                civilizations.none {
                    it.isMajorCiv() &&
                    it.isAlive() &&
                    (it.policies.isAdopted(conditional.params[0]) || it.religionManager.religion?.hasBelief(conditional.params[0]) == true)
                }
            }
            UniqueType.ConditionalAfterPolicyOrBelief ->
                checkOnCiv { policies.isAdopted(conditional.params[0]) || religionManager.religion?.hasBelief(conditional.params[0]) == true }
            UniqueType.ConditionalBeforePolicyOrBelief ->
                checkOnCiv { !policies.isAdopted(conditional.params[0]) && religionManager.religion?.hasBelief(conditional.params[0]) != true }
            UniqueType.ConditionalBeforePantheon ->
                checkOnCiv { religionManager.religionState == ReligionState.None }
            UniqueType.ConditionalAfterPantheon ->
                checkOnCiv { religionManager.religionState != ReligionState.None }
            UniqueType.ConditionalBeforeReligion ->
                checkOnCiv { religionManager.religionState < ReligionState.Religion }
            UniqueType.ConditionalAfterReligion ->
                checkOnCiv { religionManager.religionState >= ReligionState.Religion }
            UniqueType.ConditionalBeforeEnhancingReligion ->
                checkOnCiv { religionManager.religionState < ReligionState.EnhancedReligion }
            UniqueType.ConditionalAfterEnhancingReligion ->
                checkOnCiv { religionManager.religionState >= ReligionState.EnhancedReligion }
            UniqueType.ConditionalAfterGeneratingGreatProphet ->
                checkOnCiv { religionManager.greatProphetsEarned() > 0 }

            UniqueType.ConditionalBuildingBuilt ->
                checkOnCiv { cities.any { it.cityConstructions.containsBuildingOrEquivalent(conditional.params[0]) } }
            UniqueType.ConditionalBuildingNotBuilt ->
                checkOnCiv { cities.none { it.cityConstructions.containsBuildingOrEquivalent(conditional.params[0]) } }
            UniqueType.ConditionalBuildingBuiltAll ->
                checkOnCiv { cities.filter { it.matchesFilter(conditional.params[1]) }.all {
                  it.cityConstructions.containsBuildingOrEquivalent(conditional.params[0]) } }
            UniqueType.ConditionalBuildingBuiltAmount ->
                checkOnCiv { cities.count { it.cityConstructions.containsBuildingOrEquivalent(conditional.params[0])
                    && it.matchesFilter(conditional.params[2]) } >= conditional.params[1].toInt() }
            UniqueType.ConditionalBuildingBuiltByAnybody ->
                checkOnGameInfo { getCities().any { it.cityConstructions.containsBuildingOrEquivalent(conditional.params[0]) } }
            UniqueType.ConditionalBuildingNotBuiltByAnybody ->
                !checkOnGameInfo { getCities().any { it.cityConstructions.containsBuildingOrEquivalent(conditional.params[0]) } }

            // Filtered via city.getMatchingUniques
            UniqueType.ConditionalInThisCity -> checkOnCity { true }
            UniqueType.ConditionalCityFilter -> checkOnCity { matchesFilter(conditional.params[0], state.relevantCiv) }
            UniqueType.ConditionalCityConnected -> checkOnCity { isConnectedToCapital() }
            UniqueType.ConditionalCityReligion -> checkOnCity {
                religion.getMajorityReligion()
                    ?.matchesFilter(conditional.params[0], state, state.relevantCiv) == true
            }
            UniqueType.ConditionalCityNotReligion -> checkOnCity {
                religion.getMajorityReligion()
                    ?.matchesFilter(conditional.params[0], state, state.relevantCiv) != true
            }
            UniqueType.ConditionalCityMajorReligion -> checkOnCity {
                religion.getMajorityReligion()?.isMajorReligion() == true }
            UniqueType.ConditionalCityEnhancedReligion -> checkOnCity {
                religion.getMajorityReligion()?.isEnhancedReligion() == true }
            UniqueType.ConditionalCityThisReligion -> checkOnCity {
                religion.getMajorityReligion() == state.relevantCiv?.religionManager?.religion }
            UniqueType.ConditionalWLTKD -> checkOnCity { isWeLoveTheKingDayActive() }
            UniqueType.ConditionalCityWithBuilding ->
                checkOnCity { cityConstructions.containsBuildingOrEquivalent(conditional.params[0]) }
            UniqueType.ConditionalCityWithoutBuilding ->
                checkOnCity { !cityConstructions.containsBuildingOrEquivalent(conditional.params[0]) }
            UniqueType.ConditionalPopulationFilter ->
                checkOnCity { population.getPopulationFilterAmount(conditional.params[1]) >= conditional.params[0].toInt() }
            UniqueType.ConditionalExactPopulationFilter ->
                checkOnCity { population.getPopulationFilterAmount(conditional.params[1]) == conditional.params[0].toInt() }
            UniqueType.ConditionalBetweenPopulationFilter ->
                checkOnCity {population.getPopulationFilterAmount(conditional.params[2]) in conditional.params[0].toInt()..conditional.params[1].toInt() }
            UniqueType.ConditionalBelowPopulationFilter ->
                checkOnCity { population.getPopulationFilterAmount(conditional.params[1]) < conditional.params[0].toInt() }
            UniqueType.ConditionalWhenGarrisoned ->
                checkOnCity { getCenterTile().militaryUnit?.canGarrison() == true }

            UniqueType.ConditionalVsCity -> checkOnTheirCombatant { state.theirCombatant?.matchesFilter("City", false) == true }
            UniqueType.ConditionalVsUnits,  UniqueType.ConditionalVsCombatant -> checkOnTheirCombatant { state.theirCombatant?.matchesFilter(conditional.params[0]) == true }
            UniqueType.ConditionalOurUnit, UniqueType.ConditionalOurUnitOnUnit ->
                checkOnRelevantUnit { state.relevantUnit?.matchesFilter(conditional.params[0]) == true }
            UniqueType.ConditionalUnitWithPromotion -> checkOnRelevantUnit {
            state.relevantUnit!!.promotions.promotions.contains(conditional.params[0])
                    || state.relevantUnit!!.hasStatus(conditional.params[0]) }
            UniqueType.ConditionalUnitWithoutPromotion ->checkOnRelevantUnit {
            !(state.relevantUnit!!.promotions.promotions.contains(conditional.params[0])
                            || state.relevantUnit!!.hasStatus(conditional.params[0]) )}
            UniqueType.ConditionalAttacking -> checkOurCombatantAction{ state.combatAction == CombatAction.Attack}
            UniqueType.ConditionalDefending -> checkOurCombatantAction{state.combatAction == CombatAction.Defend }
            UniqueType.ConditionalAboveHP -> checkOnRelevantUnit { state.relevantUnit!!.health > conditional.params[0].toInt()
                    || state.ourCombatant != null && state.ourCombatant.getHealth() > conditional.params[0].toInt()}
            UniqueType.ConditionalBelowHP -> checkOnRelevantUnit { state.relevantUnit!!.health < conditional.params[0].toInt()
                    ||state.ourCombatant != null && state.ourCombatant.getHealth() < conditional.params[0].toInt()}
            UniqueType.ConditionalAboveMovement -> checkOnRelevantUnit { state.relevantUnit!!.currentMovement > conditional.params[0].toInt()}
            UniqueType.ConditionalBelowMovement -> checkOnRelevantUnit { state.relevantUnit!!.currentMovement < conditional.params[0].toInt()}
            UniqueType.ConditionalHasNotUsedOtherActions ->
                state.unit == null || // So we get the action as a valid action in BaseUnit.hasUnique()
                    checkOnRelevantUnit { state.unit.abilityToTimesUsed.isEmpty()}
            UniqueType.ConditionalStackedWithUnit -> checkOnRelevantUnit {
            state.relevantUnit!!.getTile().getUnits().any { it != state.relevantUnit && it.matchesFilter(conditional.params[0]) }}
            UniqueType.ConditionalNotStackedWithUnit -> state.relevantUnit == null ||
                checkOnRelevantUnit { !state.relevantUnit!!.getTile().getUnits().any { it != state.relevantUnit && it.matchesFilter(conditional.params[0]) }}

            UniqueType.ConditionalInTiles ->
                checkOnTile {state.relevantTile?.matchesFilter(conditional.params[0], state.relevantCiv) == true }
            UniqueType.ConditionalInTilesNot ->
                checkOnTile {state.relevantTile?.matchesFilter(conditional.params[0], state.relevantCiv) == false }
            UniqueType.ConditionalAdjacentTo -> checkOnTile {state.relevantTile?.isAdjacentTo(conditional.params[0], state.relevantCiv) == true }
            UniqueType.ConditionalNotAdjacentTo -> checkOnTile {state.relevantTile?.isAdjacentTo(conditional.params[0], state.relevantCiv) == false }
            UniqueType.ConditionalFightingInTiles ->
                    checkOnTile {state.attackedTile?.matchesFilter(conditional.params[0], state.relevantCiv) == true }
            UniqueType.ConditionalNearTiles ->
                checkOnTile {state.relevantTile!!.getTilesInDistance(conditional.params[0].toInt()).any {
                    it.matchesFilter(conditional.params[1], state.relevantCiv)
                }}

            UniqueType.ConditionalVsLargerCiv -> checkOnTheirCombatant {
            val yourCities = state.relevantCiv?.cities?.size ?: 1
                val theirCities = state.theirCombatant?.getCivInfo()?.cities?.size ?: 0
                yourCities < theirCities
            }
            UniqueType.ConditionalForeignContinent -> checkOnCiv {
                state.relevantTile != null && (
                    cities.isEmpty() || getCapital() == null
                        || getCapital()!!.getCenterTile().getContinent() != state.relevantTile!!.getContinent()
                    )
            }
            UniqueType.ConditionalAdjacentUnit -> checkOnTile {
                state.relevantCiv != null &&
                        state.relevantUnit != null &&
                        state.relevantTile!!.neighbors.any {
                        it.getUnits().any {
                            it != state.relevantUnit &&
                                it.civ == state.relevantCiv &&
                                it.matchesFilter(conditional.params[0])
                        }
                    }
            }

            UniqueType.ConditionalNeighborTiles ->
                checkOnTile { state.relevantTile!!.neighbors.count {
                    it.matchesFilter(conditional.params[2], state.relevantCiv)
                } in conditional.params[0].toInt()..conditional.params[1].toInt() }

            UniqueType.ConditionalOnWaterMaps -> state.region?.continentID == -1
            UniqueType.ConditionalInRegionOfType -> state.region?.type == conditional.params[0]
            UniqueType.ConditionalInRegionExceptOfType -> state.region?.type != conditional.params[0]

            UniqueType.ConditionalFirstCivToResearch ->
                unique != null
                    && unique.sourceObjectType == UniqueTarget.Tech
                    && checkOnGameInfo { civilizations.none {
                        it != state.relevantCiv && it.isMajorCiv()
                            && it.tech.isResearched(unique.sourceObjectName!!) // guarded by the sourceObjectType check
                    } }

            UniqueType.ConditionalFirstCivToAdopt ->
                unique != null
                    && unique.sourceObjectType == UniqueTarget.Policy
                    && checkOnGameInfo { civilizations.none {
                        it != state.relevantCiv && it.isMajorCiv()
                            && it.policies.isAdopted(unique.sourceObjectName!!) // guarded by the sourceObjectType check
                    } }

            UniqueType.ConditionalCountableEqualTo ->
                compareCountables(conditional.params[0], conditional.params[1]) {
                    first, second -> first == second
                }

            UniqueType.ConditionalCountableDifferentThan ->
                compareCountables(conditional.params[0], conditional.params[1]) {
                        first, second -> first != second
                }

            UniqueType.ConditionalCountableMoreThan ->
                compareCountables(conditional.params[0], conditional.params[1]) {
                        first, second -> first > second
                }

            UniqueType.ConditionalCountableLessThan ->
                compareCountables(conditional.params[0], conditional.params[1]) {
                        first, second -> first < second
                }

            UniqueType.ConditionalCountableBetween ->
                compareCountables(conditional.params[0], conditional.params[1], conditional.params[2]) {
                    first, second, third ->
                    first in second..third
                }
                
            UniqueType.ConditionalWhenCarriedBy -> {
                // Check if the unit is currently transported and being carried by matching filter
                if (state.relevantUnit == null || !state.relevantUnit!!.isTransported) false
                else {
                    val carrier = state.relevantUnit!!.getTile().militaryUnit
                    // Only true if: 1) carrier exists, 2) carrier is NOT the unit itself, 3) carrier matches filter
                    carrier != null && carrier != state.relevantUnit && 
                    carrier.matchesFilter(conditional.params[0]) == true
                }
            }
            
            UniqueType.ConditionalModEnabled -> checkOnGameInfo {
                val filter = conditional.params[0]
                (gameParameters.mods.asSequence() + gameParameters.baseRuleset).any { ModCompatibility.modNameFilter(it, filter) }
            }
            UniqueType.ConditionalModNotEnabled -> checkOnGameInfo {
                val filter = conditional.params[0]
                (gameParameters.mods.asSequence() + gameParameters.baseRuleset).none { ModCompatibility.modNameFilter(it, filter) }
            }

            else -> false
        }
    }
}
