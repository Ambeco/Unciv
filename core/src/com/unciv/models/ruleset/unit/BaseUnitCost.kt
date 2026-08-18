package com.unciv.models.ruleset.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly

class BaseUnitCost(val baseUnit: BaseUnit) {

    @Readonly
    fun getProductionCost(civInfo: Civilization, city: City?): Int {
        var productionCost = baseUnit.cost.toFloat()

        val stateForConditionals = city?.state ?: civInfo.state
        baseUnit.forEachMatchingUnique(UniqueType.CostIncreasesPerCity, stateForConditionals) {
            productionCost += civInfo.cities.size * it.params[0].toInt()
        }

        baseUnit.forEachMatchingUnique(UniqueType.CostIncreasesWhenBuilt, stateForConditionals) {
            productionCost += civInfo.civConstructions.builtItemsWithIncreasingCost[baseUnit.name] * it.params[0].toInt()
        }

        baseUnit.forEachMatchingUnique(UniqueType.CostPercentageChange, stateForConditionals) {
            productionCost *= it.params[0].toPercent()
        }

        productionCost *= if (civInfo.isCityState)
            1.5f
        else if (civInfo.isHuman())
            civInfo.getDifficulty().unitCostModifier
        else
            civInfo.gameInfo.getDifficulty().aiUnitCostModifier

        productionCost *= civInfo.gameInfo.speed.productionCostModifier
        return productionCost.toInt()
    }


    /** Contains only unit-specific uniques that allow purchasing with stat */
    @Readonly
    fun canBePurchasedWithStat(city: City, stat: Stat): Boolean {
        val conditionalState = city.state

        if (city.firstMatchingUniqueOrNull(UniqueType.BuyUnitsIncreasingCost, conditionalState) {
                        it.params[2] == stat.name
                                && baseUnit.matchesFilter(it.params[0], conditionalState)
                                && city.matchesFilter(it.params[3])
                    } != null
        ) return true

        if (city.firstMatchingUniqueOrNull(UniqueType.BuyUnitsByProductionCost, conditionalState) {
                        it.params[1] == stat.name && baseUnit.matchesFilter(it.params[0], conditionalState)
                    } != null
        )
            return true

        if (city.firstMatchingUniqueOrNull(UniqueType.BuyUnitsWithStat, conditionalState) {
                        it.params[1] == stat.name
                                && baseUnit.matchesFilter(it.params[0], conditionalState)
                                && city.matchesFilter(it.params[2])
                    } != null
        )
            return true

        if (city.firstMatchingUniqueOrNull(UniqueType.BuyUnitsForAmountStat, conditionalState) {
                        it.params[2] == stat.name
                                && baseUnit.matchesFilter(it.params[0], conditionalState)
                                && city.matchesFilter(it.params[3])
                    } != null
        )
            return true

        return false
    }

    @Readonly
    fun getStatBuyCost(city: City, stat: Stat): Int? {
        var cost = baseUnit.getBaseBuyCost(city, stat)?.toDouble() ?: return null
        val conditionalState = city.state

        city.forEachMatchingUnique(UniqueType.BuyUnitsDiscount) { unique ->
            if (stat.name == unique.params[0] && baseUnit.matchesFilter(unique.params[1], conditionalState))
                cost *= unique.params[2].toPercent()
        }
        city.forEachMatchingUnique(UniqueType.BuyItemsDiscount) { unique ->
            if (stat.name == unique.params[0])
                cost *= unique.params[1].toPercent()
        }

        return (cost / 10f).toInt() * 10
    }


    // Deliberately uses the deprecated getMatchingUniques: builds a lazy Sequence via yieldAll/yield, which has no
    // forEachMatchingUnique equivalent. TODO: followup commit will un-deprecate and rename to getMatchingUniquesSnapshot.
    @Suppress("DEPRECATION")
    @Readonly
    fun getBaseBuyCosts(city: City, stat: Stat): Sequence<Float> {
        val conditionalState = city.state
        return sequence {
            yieldAll(city.getMatchingUniques(UniqueType.BuyUnitsIncreasingCost, conditionalState)
                .filter {
                    it.params[2] == stat.name
                            && baseUnit.matchesFilter(it.params[0], conditionalState)
                            && city.matchesFilter(it.params[3])
                }.map {
                    baseUnit.getCostForConstructionsIncreasingInPrice(
                        it.params[1].toInt(),
                        it.params[4].toInt(),
                        city.civ.civConstructions.boughtItemsWithIncreasingPrice[baseUnit.name]
                    ) * city.civ.gameInfo.speed.statCostModifiers[stat]!!
                }
            )
            yieldAll(city.getMatchingUniques(UniqueType.BuyUnitsByProductionCost, conditionalState)
                .filter { it.params[1] == stat.name && baseUnit.matchesFilter(it.params[0], conditionalState) }
                .map { (getProductionCost(city.civ, city) * it.params[2].toInt()).toFloat() }
            )

            if (city.getMatchingUniques(UniqueType.BuyUnitsWithStat, conditionalState)
                        .any {
                            it.params[1] == stat.name
                                    && baseUnit.matchesFilter(it.params[0], conditionalState)
                                    && city.matchesFilter(it.params[2])
                        }
            ) yield(city.civ.getEra().baseUnitBuyCost * city.civ.gameInfo.speed.statCostModifiers[stat]!!)

            yieldAll(city.getMatchingUniques(UniqueType.BuyUnitsForAmountStat, conditionalState)
                .filter {
                    it.params[2] == stat.name
                            && baseUnit.matchesFilter(it.params[0], conditionalState)
                            && city.matchesFilter(it.params[3])
                }.map { it.params[1].toInt() * city.civ.gameInfo.speed.statCostModifiers[stat]!! }
            )
        }
    }
}
