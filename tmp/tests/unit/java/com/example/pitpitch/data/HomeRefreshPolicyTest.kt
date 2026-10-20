package com.example.pitpitch.data

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class HomeRefreshPolicyTest {
    private fun fixture(id: String, round: Int, time: String, status: String = "待赛", league: String = "eng.1") =
        sampleMatches.first().copy(id = id, competition = league, competitionId = league, stage = "第 $round 轮",
            roundNumber = round, kickoffUtc = time, date = time.take(10), status = status)

    @Test fun refreshUsesTheWholeNextVerifiedRoundAndKeepsHistoryAndLaterRoundsOut() {
        val now = Instant.parse("2026-10-20T12:00:00Z")
        val round = (1..5).map { fixture("next-$it", 9, "2026-10-2${it}T12:00:00Z") }
        val history = fixture("history", 8, "2026-10-19T12:00:00Z", "完场")
        val completedSameRound = fixture("done", 9, "2026-10-20T10:00:00Z", "完场")
        val later = fixture("later", 10, "2026-10-28T12:00:00Z")
        val hidden = fixture("hidden", 9, "2026-10-21T14:00:00Z", league = "esp.1")
        val targets = homeRefreshMatches(round + listOf(history, completedSameRound, later, hidden), setOf("esp.1"), now, ZoneOffset.UTC)
        assertEquals(round.map { it.id }, targets.map { it.id })
        assertEquals(listOf("20261021", "20261022", "20261023", "20261024", "20261025"), homeRefreshDays(targets))
    }

    @Test fun alreadyStartedLiveFixtureRemainsEligibleWhileItsRoundStillHasFutureFixtures() {
        val live = fixture("live", 9, "2026-10-20T11:30:00Z", "进行中")
        val next = fixture("next", 9, "2026-10-21T12:00:00Z")
        assertEquals(setOf("live", "next"), homeRefreshMatches(listOf(live, next), emptySet(), Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC).map { it.id }.toSet())
    }

    @Test fun singleTapNavigatesWithoutRefreshAndPairsDoNotBecomeTripleRefreshes() {
        val taps = HomeRefreshTaps(300)
        assertFalse(taps.tap(1000)); assertTrue(taps.tap(1250)); assertFalse(taps.tap(1300))
        assertFalse(taps.tap(2000)); assertFalse(taps.tap(2400)); assertTrue(taps.tap(2600))
        assertFalse(taps.tap(3000)); taps.reset(); assertFalse(taps.tap(3100))
    }

    @Test fun liveLastKickoffIsStillRefreshedAndDateOnlyFixturesUseTheirSuppliedDate() {
        val live = fixture("live", 8, "2026-10-20T11:30:00Z", "进行中")
        val next = fixture("next", 9, "2026-10-21T12:00:00Z")
        val now = Instant.parse("2026-10-20T12:00:00Z")
        assertEquals(setOf("live", "next"), homeRefreshMatches(listOf(live, next), emptySet(), now, ZoneOffset.UTC).map { it.id }.toSet())
        assertEquals(listOf("20261021"), homeRefreshDays(listOf(next.copy(kickoffUtc = null))))
    }
}
