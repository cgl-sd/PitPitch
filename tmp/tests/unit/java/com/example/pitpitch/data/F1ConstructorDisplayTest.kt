package com.example.pitpitch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class F1ConstructorDisplayTest {
    @Test fun removesOnlyTheRedundantTrailingTeamSuffix() {
        for (team in listOf("RB", "Alpine", "Haas", "Cadillac")) {
            val source = "$team F1 Team"
            assertEquals(team, f1ConstructorDisplayName(source))
            assertEquals("$team F1 Team", source)
        }
    }

    @Test fun preservesDistinctHistoricalNamesAndOtherWords() {
        for (team in listOf("Racing Point", "Red Bull", "AlphaTauri", "Alfa Romeo", "Aston Martin", "F1 Team", "Team Lotus")) {
            assertEquals(team, f1ConstructorDisplayName(team))
        }
    }
}
