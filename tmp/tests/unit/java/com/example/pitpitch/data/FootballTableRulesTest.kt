package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class FootballTableRulesTest {
    private fun table(n: Int, played: Int = 0) = FootballTable("test", (1..n).map {
        FootballStanding(FootballTeam(it, "Team $it", null), "$it", "$played", "0", "0", "0", "0", "0", "0", "0", null)
    })
    @Test fun currentPositionDoesNotMeanConfirmedResult() {
        val t = table(20, 5)
        assertEquals(StandingMark(TableZone.CHAMPIONS, false), standingMark(t.entries.first(), t, "eng.1", 2026))
        assertEquals(StandingMark(TableZone.RELEGATION, false), standingMark(t.entries.last(), t, "eng.1", 2026))
    }
    @Test fun playoffsAndFrenchShrinkSeasonAreSeparateFromRelegation() {
        val german = table(18)
        assertEquals(TableZone.RELEGATION_PLAYOFF, standingMark(german.entries[15], german, "ger.1", 2026).zone)
        assertEquals(TableZone.RELEGATION, standingMark(german.entries[16], german, "ger.1", 2026).zone)
        val france = table(20)
        assertEquals(TableZone.RELEGATION, standingMark(france.entries[16], france, "fra.1", 2022).zone)
        assertEquals(TableZone.RELEGATION_PLAYOFF, standingMark(france.entries[17], france, "fra.1", 2021).zone)
    }
    @Test fun europeanFormatsMatchSelectedSeason() {
        val modern = table(36, 1)
        assertEquals(TableZone.ADVANCE, standingMark(modern.entries[7], modern, "uefa.champions", 2026).zone)
        assertEquals(TableZone.PLAYOFF, standingMark(modern.entries[8], modern, "uefa.europa", 2026).zone)
        assertEquals(TableZone.ELIMINATED, standingMark(modern.entries[24], modern, "uefa.champions", 2026).zone)
        val old = table(4, 1)
        assertEquals(TableZone.ELIMINATED, standingMark(old.entries[2], old, "uefa.europa", 2020).zone)
        assertEquals(TableZone.CONFERENCE, standingMark(old.entries[2], old, "uefa.europa", 2021).zone)
        assertEquals(TableZone.EUROPA, standingMark(old.entries[2], old, "uefa.champions", 2021).zone)
    }
    @Test fun finalProviderNotesIncludeExtraQualificationPlaces() {
        val base = table(20, 38)
        val fifth = base.entries[4].copy(providerNote = "Champions League")
        val t = base.copy(entries = base.entries.toMutableList().apply { set(4, fifth) })
        assertEquals(StandingMark(TableZone.CHAMPIONS, true), standingMark(fifth, t, "eng.1", 2025))
        assertEquals(TableZone.RELEGATION_PLAYOFF, standingNoteZone("Relegation playoff"))
        assertEquals(TableZone.CHAMPIONS_QUALIFYING, standingNoteZone("Champions League qualifying"))
    }
    @Test fun strictPointBoundsHandleEarlyClinchesAndLeaveTiesUnconfirmed() {
        val t = table(20, 37).let { it.copy(entries = it.entries.mapIndexed { i, row -> row.copy(points = "${80 - i * 3}") }) }
        assertTrue(standingMark(t.entries.first(), t, "eng.1", 2026).confirmed)
        assertFalse(standingMark(t.entries[3], t, "eng.1", 2026).confirmed)
        assertTrue(standingMark(t.entries.last(), t, "eng.1", 2026).confirmed)
        val invalid = t.copy(entries = t.entries.map { it.copy(played = "—") })
        assertFalse(standingMark(invalid.entries.first(), invalid, "eng.1", 2026).confirmed)
    }
}
