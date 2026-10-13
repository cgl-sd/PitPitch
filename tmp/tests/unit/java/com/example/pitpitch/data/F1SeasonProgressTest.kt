package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class F1SeasonProgressTest {
    private fun race(round: Int, season: Int = 2026) = F1Race(season, round, "test", "track", "2026-12-01", null, "")
    @Test fun publishedStandingsDetermineProgressNotElapsedDates() {
        val races = (1..24).map(::race)
        val progress = f1SeasonProgress(2026, races, "18")!!
        assertEquals(18, progress.counted); assertEquals(6, progress.remaining)
        assertEquals(.75f, progress.fraction, .0001f)
        assertEquals(0, f1SeasonProgress(2026, races, "24")!!.remaining)
    }
    @Test fun countActualDistinctSeasonRoundsIncludingCalendarGaps() {
        val races = listOf(race(1), race(3), race(3), race(5), race(10, 2025))
        assertEquals(F1SeasonProgress(2, 3), f1SeasonProgress(2026, races, "3"))
        assertEquals(F1SeasonProgress(0, 3), f1SeasonProgress(2026, races, "0"))
    }
    @Test fun missingOrInconsistentDataDoesNotInventProgress() {
        for (round in listOf(null, "bad", "-1", "25")) assertNull(f1SeasonProgress(2026, (1..24).map(::race), round))
        assertNull(f1SeasonProgress(2026, emptyList(), "1"))
    }
}
