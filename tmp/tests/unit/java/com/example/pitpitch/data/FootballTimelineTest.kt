package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class FootballTimelineTest {
    @Test fun eventScoresRequireBothExactTeamNamesAndExplicitScore() {
        val match = sampleMatches.first().copy(home = "Brentford", away = "Arsenal")
        assertEquals("1-3", footballEventScore(sub("score").copy(text = "Second Half ends, Brentford 1, Arsenal 3."), match))
        assertNull(footballEventScore(sub("missing"), match))
        assertNull(footballEventScore(sub("foreign").copy(text = "Brentford 1, Chelsea 3."), match))
    }
    @Test fun compactEventsKeepGoalsRedCardsAndNodesInSourceOrder() {
        val types = listOf("kickoff", "yellow-card", "goal", "substitution", "halftime", "start-2nd-half", "second-yellow-card", "penalty---scored", "own-goal", "end-regular-time", "unknown")
        val original = types.mapIndexed { i, type -> sub(i.toString()).copy(type = type) }
        assertEquals(listOf("kickoff", "goal", "halftime", "start-2nd-half", "second-yellow-card", "penalty---scored", "own-goal", "end-regular-time"),
            compactFootballEvents(original).map { it.type })
        assertEquals(types, original.map { it.type })
    }
    private fun sub(id: String, team: Int? = 1, clock: String = "67'", period: Int? = 2) =
        FootballDetailEvent(id, "substitution", clock, 4020.0, period, team, listOf("In $id", "Out $id"), "Substitution $id")
    @Test fun consecutiveDoubleAndTripleSubstitutionsKeepEveryOriginalRecord() {
        for (count in 2..3) {
            val original = (1..count).map { sub(it.toString()) }
            val groups = footballTimelineGroups(original)
            assertEquals(1, groups.size)
            assertEquals(original, groups.single().events)
            assertEquals(original, groups.flatMap { it.events })
        }
    }
    @Test fun differentTeamsMinutesStoppageOrPeriodsAndUnknownDataStaySeparate() {
        val records = listOf(sub("1"), sub("2", team = 2), sub("3", clock = "68'"),
            sub("4", clock = "67'+1"), sub("5", period = 1), sub("6", team = null), sub("7", team = null),
            sub("8", clock = ""), sub("9", clock = ""), sub("10", period = null), sub("11", period = null))
        assertEquals(records.size, footballTimelineGroups(records).size)
        assertEquals(records, footballTimelineGroups(records).flatMap { it.events })
    }
    @Test fun cardOrGoalBetweenSubstitutionsKeepsChronologyAndDoesNotMerge() {
        val records = listOf(sub("1"), sub("card").copy(type = "yellow-card"), sub("2"), sub("goal").copy(type = "goal"), sub("3"))
        assertEquals(records.map { it.id }, footballTimelineGroups(records).map { it.first.id })
        assertTrue(footballTimelineGroups(emptyList()).isEmpty())
    }
}
