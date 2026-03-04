package com.unciv.logic.automation.unit

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques

/**
 * Queue of actions available for a unit 
 */
class UniqueActionQueue(val unit: MapUnit) {
    val actions = ArrayDeque((UnitActionsFromUniques.getTriggerUniqueActions(unit) + UnitActionsFromUniques.getTransformActions(unit))
        .filter { it.associatedUnique?.hasModifier(UniqueType.UnitActionPriority) == true }
        .sortedWith { l, r -> r.useFrequency.compareTo(l.useFrequency) }
        .toList())
    
    fun automateRemainingUniqueActions(turnsToMove: Int = 0) = automateUniqueActionsUntilUseFrequency(-Float.MAX_VALUE, turnsToMove)
    
    fun automateUniqueActionsUntilUseFrequency(useFrequency: Float, turnsToMove: Int = 0): Boolean {
        while (unit.hasMovement() && actions.isNotEmpty() && actions.first().useFrequency > useFrequency) {
            val action = actions.removeFirst()
            if (action.visible()) {
                action.invoke() // we can use the action right now. Do it.
            } else if (action.availableOnTile != null && unit.hasMovement()) {
                // search for a target
                val target = unit.movement.bfsUntilMatchingTile(turnsToMove) { tile, _ -> action.availableOnTile(tile) }
                if (target == null) continue // failed to find valid target. try next action
                // found a target. Move toward it.
                unit.movement.headTowards(target)
                if (unit.movement.canUnitSwapTo(target)) {
                    unit.movement.swapMoveToTile(target)
                }
                // now use the ability if able
                if (unit.currentTile == target && unit.hasMovement() && action.visible())
                    action.invoke()
            }
        }
        return !unit.hasMovement() || unit.isDestroyed
    }
}
