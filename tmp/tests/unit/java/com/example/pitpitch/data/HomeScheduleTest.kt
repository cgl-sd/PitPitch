package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HomeScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-04T16:00:00Z")
    private val monday = LocalDate.parse("2026-10-05")
    private fun fixture(id: String, round: Int, utc: String) = sampleMatches.first().copy(id = id, stage = "常规联赛 · 轮次未提供",
        competition = "英超", competitionId = "eng.1", roundNumber = round, kickoffUtc = utc, status = "待赛")
    @Test fun roundSpanningMondayIsNotCutAtSundayOrMixedWithMidweekAndWeekend() {
        val six = listOf(fixture("sun", 6, "2026-10-11T12:00:00Z"), fixture("mon", 6, "2026-10-12T19:00:00Z"))
        val next = fixture("midweek", 7, "2026-10-14T19:00:00Z")
        val weekend = fixture("weekend", 8, "2026-10-17T12:00:00Z")
        val rows = homeFootballRounds(six + next + weekend, zone, Instant.parse("2026-10-11T04:00:00Z"))
        assertEquals("第 6 轮", rows.single().stage)
        assertEquals(listOf("sun", "mon"), rows.single().matches.map { it.id })
    }
    @Test fun finalKickoffClosesRoundEvenWhenProviderStillReportsLive() {
        val last = fixture("last", 6, "2026-10-12T19:00:00Z").copy(status = "进行中")
        val next = fixture("next", 7, "2026-10-14T19:00:00Z")
        val cutoff = Instant.parse(last.kickoffUtc)
        assertEquals(6, homeFootballRounds(listOf(last, next), zone, cutoff).single().matches.first().roundNumber)
        val result = homeFootballRounds(listOf(last, next), zone, cutoff.plusNanos(1)).single()
        assertEquals(7, result.matches.first().roundNumber)
        assertTrue(result.preview)
        assertEquals("进行中", last.status)
    }
    @Test fun midweekEndAdvancesImmediatelyToWeekendRoundInSameWeek() {
        val midweek = fixture("wed", 7, "2026-10-14T19:00:00Z")
        val weekend = fixture("sat", 8, "2026-10-17T12:00:00Z")
        assertEquals(listOf("sat"), homeFootballRounds(listOf(midweek, weekend), zone,
            Instant.parse("2026-10-14T19:00:01Z")).single().matches.map { it.id })
    }
    @Test fun postponedCancelledAndOlderMakeupsDoNotHoldCurrentRound() {
        val past = fixture("closed-five", 5, "2026-10-03T12:00:00Z")
        val makeup = fixture("old-two", 2, "2026-10-05T12:00:00Z")
        val current = fixture("six", 6, "2026-10-06T12:00:00Z")
        val postponed = fixture("postponed", 5, "2026-12-01T12:00:00Z").copy(status = "延期")
        val cancelled = fixture("cancelled", 6, "2026-12-01T12:00:00Z").copy(status = "取消")
        assertEquals(listOf("six"), homeFootballRounds(listOf(makeup, current, past, postponed, cancelled), zone, now).single().matches.map { it.id })
    }
    @Test fun championsLeagueUsesWholeMatchdayAcrossGroupsAndAdvancesAfterLastKickoff() {
        val groupA = fixture("a", 2, "2026-10-13T19:00:00Z").copy(competition = "欧冠", competitionId = "uefa.champions", stage = "小组 A")
        val groupB = groupA.copy(id = "b", stage = "小组 B", kickoffUtc = "2026-10-14T19:00:00Z")
        val next = groupA.copy(id = "next", roundNumber = 3, kickoffUtc = "2026-10-27T20:00:00Z")
        val rows = homeFootballRounds(listOf(next, groupB, groupA), zone, Instant.parse("2026-10-13T10:00:00Z"))
        assertEquals("小组赛 · 第 2 轮", rows.single().stage)
        assertEquals(listOf("a", "b"), rows.single().matches.map { it.id })
        assertEquals(3, homeFootballRounds(listOf(next, groupB, groupA), zone,
            Instant.parse("2026-10-14T19:00:01Z")).single().matches.first().roundNumber)
        assertEquals("小组 B", groupB.stage)
    }
    @Test fun cutoffUsesFullRoundNotOnlyThreeVisibleFixtures() {
        val matches = (1..4).map { fixture("match-$it", 6, "2026-10-${10 + it}T19:00:00Z") }
        assertEquals(6, homeFootballRounds(matches, zone, Instant.parse("2026-10-13T20:00:00Z")).single().matches.first().roundNumber)
        assertTrue(homeFootballRounds(matches, zone, Instant.parse("2026-10-14T19:00:01Z")).isEmpty())
    }
    @Test fun finishedAndPastKickoffsDisappearWithoutChangingRecords() {
        val finished = fixture("finished", 6, "2026-10-10T11:30:00Z").copy(status = "完场", score = "2 : 0")
        val stale = fixture("stale", 6, "2026-10-10T14:00:00Z")
        val future = fixture("future", 6, "2026-10-11T12:00:00Z")
        assertEquals(listOf("future"), homeFootballRounds(listOf(finished, stale, future), zone,
            Instant.parse("2026-10-11T04:00:00Z")).single().matches.map { it.id })
        assertEquals("2 : 0", finished.score)
        assertEquals("待赛", stale.status)
    }
    @Test fun fixturesAndCompetitionsAreOrderedByExactKickoff() {
        val late = fixture("late", 6, "2026-10-11T14:00:00Z")
        val first = fixture("first", 6, "2026-10-11T10:00:00Z")
        val other = fixture("other", 2, "2026-10-11T11:00:00Z").copy(competition = "欧联", competitionId = "uefa.europa", stage = "联赛阶段")
        val rounds = homeFootballRounds(listOf(late, other, first), zone, Instant.parse("2026-10-11T04:00:00Z"))
        assertEquals(listOf("first", "late"), rounds.first().matches.map { it.id })
        assertEquals("欧联", rounds.last().competition)
    }
    @Test fun unknownTimeHasNoInventedExactDeadlineAndUsesDeviceDate() {
        val unknown = fixture("unknown", 6, "2026-10-11T10:00:00Z").copy(kickoffUtc = null, date = "2026-10-10")
        val clock = Instant.parse("2026-10-10T17:00:00Z")
        assertTrue(homeFootballRounds(listOf(unknown), zone, clock).isEmpty())
        assertEquals(listOf("unknown"), homeFootballRounds(listOf(unknown), ZoneOffset.UTC, clock).single().matches.map { it.id })
    }
    @Test fun missingRoundIsNotInferredFromCalendarWeek() {
        val next = fixture("next", 7, "2026-10-13T12:00:00Z").copy(roundNumber = null)
        val later = next.copy(id = "later", kickoffUtc = "2026-10-20T12:00:00Z")
        val row = homeFootballRounds(listOf(later, next), zone, now).single()
        assertEquals("常规联赛 · 轮次未提供", row.stage)
        assertTrue(row.matches.all { it.roundNumber == null })
    }
    @Test fun invalidDatesAreExcluded() {
        assertTrue(homeFootballRounds(listOf(sampleMatches.first().copy(date = "invalid")), zone, now).isEmpty())
    }
    @Test fun raceRemainsSelectedAcrossWeekBoundaryUntilCalendarCutoff() {
        val race = F1Race(2026, 10, "Test", "Track", "2026-10-11", "20:00:00Z", "Test", listOf(F1Session("冲刺赛", "2026-10-10", "12:00:00Z")))
        val result = homeRace(listOf(race), zone, now)!!
        assertEquals(race, result)
        for (clock in listOf("2026-10-11T15:59:00Z", "2026-10-11T16:01:00Z")) {
            assertEquals(race, homeRace(listOf(race), zone, Instant.parse(clock)))
        }
        assertNull(homeRace(listOf(race), zone, Instant.parse("2026-10-11T20:00:01Z")))
        assertEquals(LocalDate.parse("2026-10-12"), sessionDay(F1Session("正赛", race.date, race.utcTime), zone))
    }
    @Test fun endedWeekendMovesToNextRaceAndUsesNextSessionForOrdering() {
        val ended = F1Race(2026, 10, "Ended", "Track", "2026-10-10", "12:00:00Z", "Test")
        val next = ended.copy(round = 11, name = "Next", date = "2026-10-18", sessions = listOf(F1Session("练习赛", "2026-10-16", "12:00:00Z")))
        val clock = Instant.parse("2026-10-11T04:00:00Z")
        assertEquals(next, homeRace(listOf(ended, next), zone, clock))
        assertEquals(Instant.parse("2026-10-16T12:00:00Z"), homeRaceTime(next, zone, clock))
    }
    @Test fun knownTitlesAreLocalizedWithoutChangingProviderGrouping() {
        val row = HomeFootballRound("德国 · 1. Fußball-Bundesliga 2026/2027", "5. Spieltag", emptyList())
        assertEquals("德甲 · 第 5 轮", row.title)
        assertEquals("5. Spieltag", row.stage)
        assertEquals("F1 New Race", homeRaceName("F1 New Race"))
        assertEquals("新加坡大奖赛", homeRaceName("Singapore Grand Prix"))
    }
}
