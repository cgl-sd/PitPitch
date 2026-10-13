package com.example.pitpitch.data

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class FootballScheduleTest {
    @Test fun postponedDatesDoNotReorderOfficialRounds() {
        val first = sampleMatches.first().copy(stage = "2. Spieltag", date = "2026-11-01")
        val later = first.copy(stage = "10. Spieltag", date = "2026-10-01")
        assertEquals(listOf("2. Spieltag", "10. Spieltag"), footballStages(listOf(later, first, later)))
        assertEquals("第 2 轮", footballStageLabel(first.stage))
        assertEquals("半决赛次回合", footballStageLabel("半决赛次回合"))
    }
    @Test fun currentRoundUsesLocalDateAndExcludesCanceledFixtures() {
        val first = sampleMatches.first().copy(stage = "2. Spieltag", kickoffUtc = "2026-10-09T16:30:00Z", status = "待赛")
        val canceled = first.copy(stage = "1. Spieltag", kickoffUtc = "2026-10-09T16:00:00Z", status = "取消")
        assertEquals("2. Spieltag", currentFootballStage(listOf(canceled, first), LocalDate.parse("2026-10-10"), ZoneId.of("Asia/Shanghai")))
        assertNull(currentFootballStage(emptyList(), LocalDate.now(), ZoneOffset.UTC))
    }
    @Test fun nextFixtureUsesExactClockAndDoesNotPickCompletedOrStalePending() {
        val base = sampleMatches.first().copy(status = "待赛", stage = "第 6 轮")
        val now = Instant.parse("2026-10-11T04:00:00Z")
        val old = base.copy(id = "old", kickoffUtc = "2026-10-11T03:59:00Z")
        val completed = base.copy(id = "completed", kickoffUtc = "2026-10-11T06:00:00Z", status = "完场")
        val late = base.copy(id = "late", kickoffUtc = "2026-10-11T12:00:00Z")
        val next = base.copy(id = "next", kickoffUtc = "2026-10-11T11:00:00Z")
        assertEquals("next", nextFootballMatch(listOf(late, old, completed, next), now, ZoneId.of("Asia/Shanghai"))?.id)
        assertNull(nextFootballMatch(listOf(old, completed), now, ZoneOffset.UTC))
        assertEquals("轮次未提供", footballRoundNavigationLabel("常规联赛 · 轮次未提供"))
        assertEquals("第 6 轮", footballRoundNavigationLabel("第 6 轮"))
    }
}
