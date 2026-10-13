package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class FootballRoundsTest {
    private val match = sampleMatches.first().copy(id = "fb-espn-eng.1-1", competitionId = "eng.1",
        stage = "常规联赛 · 轮次未提供", homeTeamId = 1000001, awayTeamId = 1000002, isDemo = false)
    private val info = FootballRoundInfo(match.id, "eng.1", 2026, 1000001, 1000002, "", 6, "https://example.com/fixture")
    @Test fun onlySupplementalFieldsChangeAndRoundControlsUseMetadata() {
        val result = applyFootballRound(match, 2026, info)
        assertEquals(match, result.copy(roundNumber = null, roundSource = null))
        assertEquals("第 6 轮", footballRoundKey(result))
        assertEquals(listOf("第 2 轮", "第 6 轮"), footballStages(listOf(result, result.copy(roundNumber = 2))))
    }
    @Test fun mismatchedIdentitySeasonAndDemoAreRejected() {
        for (bad in listOf(info.copy(matchId = "other"), info.copy(season = 2025),
            info.copy(competitionId = "esp.1"), info.copy(homeTeamId = 1000002), info.copy(number = 0), info.copy(number = 39), info.copy(source = "http://example.com"))) {
            assertEquals(match, applyFootballRound(match, 2026, bad))
        }
        val demo = match.copy(isDemo = true)
        assertEquals(demo, applyFootballRound(demo, 2026, info))
    }
    @Test fun europeanGroupsShareMatchdayButRetainGroupAndKnockoutsStaySeparate() {
        val group = match.copy(competitionId = "uefa.champions", stage = "小组 A")
        val round = info.copy(competitionId = "uefa.champions", season = 2023,
            providerNote = "UEFA Champions League, Group A", number = 2)
        val result = applyFootballRound(group, 2023, round)
        assertEquals("小组赛 · 第 2 轮", footballRoundKey(result))
        assertEquals("小组 A", result.stage)
        assertEquals(group, applyFootballRound(group, 2023, round.copy(providerNote = "UEFA Champions League, Group B")))
        assertEquals(group, applyFootballRound(group, 2023, round.copy(number = 7)))
        val knockout = group.copy(stage = "八强", leg = 2)
        assertEquals(knockout, applyFootballRound(knockout, 2023, round))
    }
}
