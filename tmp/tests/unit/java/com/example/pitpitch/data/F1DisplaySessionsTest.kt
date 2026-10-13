package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class F1DisplaySessionsTest {
    private val first = F1Session("第一次练习赛", "2026-10-16", "10:00:00Z")
    private val second = F1Session("第二次练习赛", "2026-10-16", "14:00:00Z")
    private val third = F1Session("第三次练习赛", "2026-10-17", "10:00:00Z")
    private val qualifying = F1Session("排位赛", "2026-10-17", "14:00:00Z")
    private val sprint = F1Session("冲刺赛", "2026-10-17", "12:00:00Z")
    private val race = F1Race(2026, 1, "Test", "Track", "2026-10-18", "14:00:00Z", "Test",
        listOf(third, qualifying, second, first, sprint))

    @Test fun everySeasonUsesOnePracticeWithoutChangingOriginalRecordsOrBookingIdentity() {
        for (year in 2020..2026) {
            val source = race.copy(season = year)
            val visible = source.displaySessions(includeRace = true)
            assertEquals(listOf("练习赛", "冲刺赛", "排位赛", "正赛"), visible.map { it.displayTitle() })
            assertSame(first, visible.first())
            assertEquals("第一次练习赛", visible.first().title)
            assertEquals(race.sessions, source.sessions)
            assertEquals(1, source.copy(sessions = source.sessions + F1Session("正赛", source.date, source.utcTime))
                .displaySessions(includeRace = true).count { it.title == "正赛" })
        }
    }
    @Test fun missingFirstUsesEarliestProvidedPracticeAndMissingTimeStaysUnknown() {
        assertEquals(second, race.copy(sessions = listOf(third, second)).displaySessions().single())
        val unknown = first.copy(time = null)
        assertSame(unknown, race.copy(sessions = listOf(second, unknown)).displaySessions().single())
        assertTrue(race.copy(sessions = listOf(qualifying)).displaySessions().none { it.displayTitle() == "练习赛" })
        assertEquals("练习赛", race.copy(sessions = listOf(first.copy(title = "练习赛"))).displaySessions().single().displayTitle())
    }
    @Test fun homeOrderingSkipsHiddenPracticesAndSprint() {
        assertEquals(listOf(first, qualifying), race.displaySessions(homePreview = true))
        assertEquals(Instant.parse("2026-10-17T14:00:00Z"), homeRaceTime(race, ZoneOffset.UTC, Instant.parse("2026-10-16T11:00:00Z")))
    }
}
