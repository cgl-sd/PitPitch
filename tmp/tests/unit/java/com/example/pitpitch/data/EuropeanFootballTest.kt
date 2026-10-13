package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class EuropeanFootballTest {
    @Test fun fixedArchiveStartAndCompetitionIdentitiesAreStable() {
        assertEquals((2020..2026).toList(), footballArchiveYears(2026).toList())
        assertEquals((2020..2026).toList(), f1ArchiveYears(2026).toList())
        assertEquals(2020, footballArchiveYears(2031).first)
        assertEquals(12, f1ArchiveYears(2031).count())
        assertTrue(footballArchiveYears(2019).isEmpty())
        assertEquals(7, footballCompetitions.map { it.id }.toSet().size)
        assertEquals(2, footballCompetitions.count { it.european })
    }
    @Test fun europePhaseAndLegPairingDoNotInventTournamentProgress() {
        assertEquals("小组 A", europeanStage("UEFA Champions League, Group A"))
        assertEquals("小组 L", europeanStage("UEFA EUROPA LEAGUE - GROUP L"))
        assertEquals("资格赛附加赛", europeanStage("UEFA Champions League, Playoffs"))
        assertEquals("淘汰赛附加赛", europeanStage("UEFA Champions League, Knockout Round Playoffs"))
        assertEquals("联赛阶段", europeanStage("UEFA Europa League, League Phase"))
        assertEquals("16 强", europeanStage("UEFA Champions League, Round of 16"))
        assertFalse(isKnockoutStage("联赛阶段")); assertTrue(isKnockoutStage("半决赛"))
        val first = sampleMatches[0].copy(stage = "半决赛", homeTeamId = 1, awayTeamId = 2, leg = 1)
        assertEquals(knockoutTieKey(first), knockoutTieKey(first.copy(homeTeamId = 2, awayTeamId = 1, leg = 2)))
        assertNotEquals(knockoutTieKey(first), knockoutTieKey(first.copy(stage = "决赛")))
    }
    @Test fun supplierUtcMinutePrecisionKeepsKickoffTime() {
        assertEquals("2025-01-01T17:30:00Z", parseProviderInstant("2025-01-01T17:30Z"))
        assertEquals("2025-01-01T17:30:00Z", parseProviderInstant("2025-01-02T01:30:00+08:00"))
        assertNull(parseProviderInstant("time TBD"))
    }
    @Test fun trackProjectionPreservesLocalScaleAndDoesNotStretch() {
        val track = projectedTrack(listOf(TrackPoint(0.0, 60.0), TrackPoint(1.0, 60.0), TrackPoint(1.0, 61.0)))
        assertEquals(1.0, track.maxOf { it.y }, 0.0001)
        assertTrue(track.maxOf { it.x } in 0.48..0.51)
        assertTrue(track.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
        assertEquals(emptyList<TrackPoint>(), projectedTrack(listOf(TrackPoint(2.0, 2.0), TrackPoint(2.0, 2.0))))
    }
}
