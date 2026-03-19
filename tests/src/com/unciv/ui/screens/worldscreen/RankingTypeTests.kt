package com.unciv.ui.screens.worldscreen

import com.unciv.testing.GdxTestRunner
import com.unciv.ui.screens.victoryscreen.RankingType
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class RankingTypeTests {

    @Test
    fun checkIdForSerializationUniqueness() {
        val uniqueIds = HashSet<Char>()
        for (rankingType in RankingType.entries) {
            val id = rankingType.idForSerialization
            Assert.assertTrue(
                "Id $id for RankingType $rankingType is not unique",
                uniqueIds.add(id)
            )
        }
    }
}
