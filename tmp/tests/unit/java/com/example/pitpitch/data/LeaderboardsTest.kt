package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class LeaderboardsTest {
    private fun player(name: String, goals: String?, assists: String?, apps: String?) =
        FootballPlayer("id-$name", name, null, "前锋", goals, apps, assists)

    @Test fun aggregationRanksByGoalsThenAssistsAndKeepsMissingUnknown() {
        val entries = aggregateLeaderboards(listOf(
            "阿森纳" to FootballSquad(2024, listOf(player("萨卡", "12", "8", "30"), player("热苏斯", null, "3", "18"))),
            "切尔西" to FootballSquad(2024, listOf(player("帕尔默", "12", "9", "31"))),
        ))
        assertEquals(listOf("帕尔默", "萨卡", "热苏斯"), entries.map { it.name })
        assertNull(entries.last().goals)
        assertEquals("阿森纳", entries[1].team)
        assertEquals(listOf("id-帕尔默", "id-萨卡", "id-热苏斯"), entries.map { it.playerId })
        assertEquals(listOf(31, 30, 18), entries.map { it.appearances })
    }
}
