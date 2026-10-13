package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class TeamProfilePresentationTest {
    private fun fixture(id: String, home: Int = 1, away: Int = 2, league: String = "eng.1", venue: String = "Ground") =
        sampleMatches.first().copy(id = id, isDemo = false, homeTeamId = home, awayTeamId = away, competitionId = league,
            stadium = venue, status = "待赛", kickoffUtc = "2026-10-31T19:00:00Z", date = "2026-10-31")
    @Test fun venueUsesOnlyUnambiguousPublishedHomeFixturesOfRouteCompetition() {
        val source = listOf(fixture("home"), fixture("away", home = 2, away = 1, venue = "Opponent ground"),
            fixture("europe", league = "uefa.champions", venue = "Neutral ground"), fixture("missing", venue = "场地未提供"))
        assertEquals("Ground", teamHomeFixtureVenue(source, 1, "eng.1"))
        assertNull(teamHomeFixtureVenue(source + fixture("moved", venue = "Temporary ground"), 1, "eng.1"))
        assertNull(teamHomeFixtureVenue(listOf(fixture("demo").copy(isDemo = true)), 1, "eng.1"))
        assertNull(teamHomeFixtureVenue(source, 99, "eng.1"))
    }
    @Test fun filtersKeepSourceFieldsAndDoNotBorrowOtherTeams() {
        val home = fixture("home"); val away = fixture("away", home = 2, away = 1)
        val euro = fixture("europe", league = "uefa.champions")
        val foreign = fixture("other", home = 3, away = 4)
        val source = listOf(home, away, euro, foreign)
        assertEquals(listOf(home, away, euro), teamProfileMatches(source, 1, null, 0))
        assertEquals(listOf(home), teamProfileMatches(source, 1, "eng.1", 1))
        assertEquals(listOf(away), teamProfileMatches(source, 1, "eng.1", 2))
        assertEquals(listOf(euro), teamProfileMatches(source, 1, "uefa.champions", 0))
        assertTrue(teamProfileMatches(source, 99, null, 0).isEmpty())
    }
    @Test fun statisticsComeFromPublishedIdentityAndMissingValuesStayUnknown() {
        val row = FootballStanding(FootballTeam(1, "Team", null), "7", "8", "4", "2", "2", "11", "9", "2", "14", null)
        val table = FootballTable("Table", listOf(row))
        assertSame(row, teamPublishedStanding(listOf(table), 1))
        assertNull(teamPublishedStanding(listOf(table), 2))
        assertNull(teamPublishedStanding(listOf(table, table), 1))
        assertNull(teamPublishedStanding(emptyList(), 1))
    }
    @Test fun monthUsesDeviceTimezoneAndCancelledFixturesStayOutOfUpcoming() {
        val match = fixture("time")
        assertEquals(YearMonth.of(2026, 11), teamFixtureMonth(match, ZoneId.of("Asia/Shanghai")))
        assertEquals(YearMonth.of(2026, 10), teamFixtureMonth(match, ZoneOffset.UTC))
        assertNull(teamFixtureMonth(match.copy(kickoffUtc = null, date = "invalid"), ZoneOffset.UTC))
        val now = Instant.parse("2026-10-31T10:00:00Z")
        assertTrue(teamFixtureIsUpcoming(match, now, ZoneOffset.UTC))
        for (status in listOf("延期", "取消", "完场")) assertFalse(teamFixtureIsUpcoming(match.copy(status = status), now, ZoneOffset.UTC))
        assertEquals("2026-10-31T19:00:00Z", match.kickoffUtc)
    }
}
