package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class LeaderboardsTest {
    private fun entry(name: String, goals: Int?, assists: Int?, apps: Int?, team: String = "阿森纳") =
        LeaderEntry(name, team, goals, assists, apps, teamId = null, playerId = "id-$name")

    @Test fun rankingOrdersByGoalsThenAssistsAndKeepsMissingUnknown() {
        val entries = listOf(
            entry("萨卡", 12, 8, 30), entry("热苏斯", null, 3, 18), entry("帕尔默", 12, 9, 31, "切尔西"),
        ).rankLeaderboard()
        assertEquals(listOf("帕尔默", "萨卡", "热苏斯"), entries.map { it.name })
        assertNull(entries.last().goals)
        assertEquals("阿森纳", entries[1].team)
        assertEquals(listOf(31, 30, 18), entries.map { it.appearances })
    }
}
