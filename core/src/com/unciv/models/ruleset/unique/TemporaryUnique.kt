@file:Suppress("ReplaceManualRangeWithIndicesCalls") // performance critical, so we iterate by index to avoid the iterator

package com.unciv.models.ruleset.unique

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.ruleset.unique.UniqueMap.Companion.MATCH_ANY_UNIQUE
import yairm210.purity.annotations.Readonly

class TemporaryUnique() : IsPartOfGameInfoSerialization {

    constructor(uniqueObject: Unique, turns: Int) : this() {
        val turnsText = uniqueObject.getModifiers(UniqueType.ConditionalTimedUnique).first().text
        unique = uniqueObject.text.replaceFirst("<$turnsText>", "").trim()
        sourceObjectType = uniqueObject.sourceObjectType
        sourceObjectName = uniqueObject.sourceObjectName
        turnsLeft = turns
    }

    var unique: String = ""

    private var sourceObjectType: UniqueTarget? = null
    private var sourceObjectName: String? = null

    @delegate:Transient
    val uniqueObject: Unique by lazy { Unique(unique, sourceObjectType, sourceObjectName) }

    var turnsLeft: Int = 0
}


fun ArrayList<TemporaryUnique>.endTurn() {
    for (unique in this) {
        if (unique.turnsLeft >= 0)
            unique.turnsLeft -= 1
    }
    removeAll { it.turnsLeft == 0 }
}

@Readonly
fun ArrayList<TemporaryUnique>.getMatchingTagUniques(uniqueType: UniqueType, gameContext: GameContext): Sequence<Unique> {
    return this.asSequence()
        .map { it.uniqueObject }
        .filter { it.type == uniqueType && it.conditionalsApply(gameContext) }
        .flatMap { it.getMultiplied(gameContext) }
}

@Readonly
fun ArrayList<TemporaryUnique>.forEachMatchingUnique(uniqueType: UniqueType, gameContext: GameContext, op: (unique: Unique)->Unit) {
    for (i in 0..<size) {
        val unique = get(i).uniqueObject
        if (unique.type == uniqueType && unique.conditionalsApply(gameContext))
            unique.forEachMultiplied(gameContext, op)
    }
}

@Readonly
fun ArrayList<TemporaryUnique>.firstMatchingUnique(uniqueType: UniqueType, gameContext: GameContext, predicate: (unique: Unique)->Boolean=MATCH_ANY_UNIQUE): Unique? {
    for (i in 0..<size) {
        val unique = get(i).uniqueObject
        if (unique.type == uniqueType && unique.conditionalsApply(gameContext) && predicate(unique))
            return unique
    }
    return null
}
