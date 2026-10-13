package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DayRefreshTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun match(id: String, status: String, score: String, kickoff: String?, liveDetail: String? = null) =
        FootballMatch(id = id, competition = "英超", stage = "常规联赛", home = "主队", away = "客队",
            homeShort = "主", awayShort = "客", score = score, status = status, stadium = "球场",
            date = kickoff?.take(10) ?: "2026-10-11", kickoffUtc = kickoff, homeTeamId = 1, awayTeamId = 2,
            competitionId = "eng.1", liveDetail = liveDetail)

    @Test fun beijingDaySpansThreeUtcDatesToCoverLastNight() {
        // 2026-10-11 15:00 Beijing: window covers Beijing yesterday+today = UTC 09, 10, 11.
        assertEquals(listOf("20261009", "20261010", "20261011"),
            overlayUtcDays(Instant.parse("2026-10-11T07:00:00Z"), shanghai))
    }

    @Test fun beijingMidnightBoundaryKeepsSameWindow() {
        assertEquals(listOf("20261009", "20261010", "20261011"),
            overlayUtcDays(Instant.parse("2026-10-10T16:00:00Z"), shanghai))
    }

    @Test fun utcZoneNeedsOnlyTwoUtcDates() {
        assertEquals(listOf("20261010", "20261011"),
            overlayUtcDays(Instant.parse("2026-10-11T03:00:00Z"), ZoneId.of("UTC")))
    }

    @Test fun liveStatusKeepsProgressFilterWorking() {
        val live = match("m1", "进行中", "1 : 0", "2026-10-11T15:00:00Z", "45'")
        val done = match("m2", "完场", "2 : 0", "2026-10-11T13:00:00Z")
        assertEquals(listOf("m1"), filterMatches(listOf(live, done), "全部", 1, "").map { it.id })
    }
}
