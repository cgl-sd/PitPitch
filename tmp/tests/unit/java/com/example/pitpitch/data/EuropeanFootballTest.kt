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
    }
    @Test fun supplierUtcMinutePrecisionKeepsKickoffTime() {
        assertEquals("2025-01-01T17:30:00Z", parseProviderInstant("2025-01-01T17:30Z"))
        assertEquals("2025-01-01T17:30:00Z", parseProviderInstant("2025-01-02T01:30:00+08:00"))
        assertNull(parseProviderInstant("time TBD"))
    }
}
