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
        for (unique in baseUnit.getMatchingUniques(UniqueType.CostIncreasesPerCity, stateForConditionals))
            productionCost += civInfo.cities.size * unique.params[0].toInt()

        for (unique in baseUnit.getMatchingUniques(UniqueType.CostIncreasesWhenBuilt, stateForConditionals))
            productionCost += civInfo.civConstructions.builtItemsWithIncreasingCost[baseUnit.name] * unique.params[0].toInt()

        for (unique in baseUnit.getMatchingUniques(UniqueType.CostPercentageChange, stateForConditionals))
            productionCost *= unique.params[0].toPercent()

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

        if (city.hasMatchingUnique(UniqueType.BuyUnitsIncreasingCost, conditionalState) {
                it.params[2] == stat.name
                && baseUnit.matchesFilter(it.params[0], conditionalState)
                && city.matchesFilter(it.params[3]) }
        ) return true

        if (city.hasMatchingUnique(UniqueType.BuyUnitsByProductionCost, conditionalState)
                { it.params[1] == stat.name && baseUnit.matchesFilter(it.params[0], conditionalState) }
        )
            return true

        if (city.hasMatchingUnique(UniqueType.BuyUnitsWithStat, conditionalState) {
                it.params[1] == stat.name
                && baseUnit.matchesFilter(it.params[0], conditionalState)
                && city.matchesFilter(it.params[2]) }
        )
            return true

        if (city.hasMatchingUnique(UniqueType.BuyUnitsForAmountStat, conditionalState) {
                it.params[2] == stat.name
                && baseUnit.matchesFilter(it.params[0], conditionalState)
                && city.matchesFilter(it.params[3])}
        )
            return true

        return false
    }

    @Readonly
    fun getStatBuyCost(city: City, stat: Stat): Int? {
        var cost = baseUnit.getBaseBuyCost(city, stat)?.toDouble() ?: return null
        val conditionalState = city.state

        city.forEachMatchingUnique(UniqueType.BuyUnitsDiscount) { unique->
            if (stat.name == unique.params[0] && baseUnit.matchesFilter(unique.params[1], conditionalState))
                cost *= unique.params[2].toPercent()
        }
        city.forEachMatchingUnique(UniqueType.BuyItemsDiscount) { unique->
            if (stat.name == unique.params[0])
                cost *= unique.params[1].toPercent()
        }
        return (cost / 10f).toInt() * 10
    }


    @Readonly
    fun getBaseBuyCosts(city: City, stat: Stat): Sequence<Float> {
        val conditionalState = city.state
        val costs = ArrayList<Float>()
        city.forEachMatchingUnique(UniqueType.BuyUnitsIncreasingCost, conditionalState) {
            if (!(it.params[2] == stat.name
                        && baseUnit.matchesFilter(it.params[0], conditionalState)
                        && city.matchesFilter(it.params[3])))
                return@forEachMatchingUnique
            val cost = baseUnit.getCostForConstructionsIncreasingInPrice(
                it.params[1].toInt(),
                it.params[4].toInt(),
                city.civ.civConstructions.boughtItemsWithIncreasingPrice[baseUnit.name]
                ) * city.civ.gameInfo.speed.statCostModifiers[stat]!!
            costs.add(cost)
        }
       city.forEachMatchingUnique(UniqueType.BuyUnitsByProductionCost, conditionalState) {
           if (!(it.params[1] == stat.name && baseUnit.matchesFilter(it.params[0], conditionalState)))
               return@forEachMatchingUnique
           costs.add((getProductionCost(city.civ, city) * it.params[2].toInt()).toFloat())
       }
        city.forEachMatchingUnique(UniqueType.BuyUnitsWithStat, conditionalState) {
            if (!(it.params[1] == stat.name
                                    && baseUnit.matchesFilter(it.params[0], conditionalState)
                                    && city.matchesFilter(it.params[2])))
                return@forEachMatchingUnique
            costs.add(city.civ.getEra().baseUnitBuyCost * city.civ.gameInfo.speed.statCostModifiers[stat]!!)
        }
        city.forEachMatchingUnique(UniqueType.BuyUnitsForAmountStat, conditionalState) {
            if (!(it.params[2] == stat.name
                            && baseUnit.matchesFilter(it.params[0], conditionalState)
                            && city.matchesFilter(it.params[3])))
                return@forEachMatchingUnique
            costs.add(it.params[1].toInt() * city.civ.gameInfo.speed.statCostModifiers[stat]!!)
        }
        return costs.asSequence()
    }
}
