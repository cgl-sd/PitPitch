package com.example.pitpitch.data

import com.example.pitpitch.data.repository.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LocalDeliveryTest {
    @Test fun utcConversionCrossesYearBoundary() {
        val formatted = formatRaceTime("2025-12-31", "23:30:00Z", ZoneId.of("Asia/Shanghai"))
        assertTrue(formatted.contains("01月01日"))
        assertTrue(formatted.contains("07:30"))
        assertTrue(formatRaceTime("2025-12-31", "23:30:00Z", ZoneId.of("UTC")).contains("23:30"))
    }
    @Test fun absentTimeDoesNotInventCountdownOrZeroTime() {
        val race = F1Race(2026, 1, "Test", "Circuit", "2026-12-31", null, "")
        assertNull(race.daysUntil())
        assertTrue(race.localTime().contains("时间待定"))
    }
    @Test fun eventAddedTimeOrdersCorrectlyAndUnavailableHasNoEvents() {
        val events = listOf(MatchEvent(90, 3, "b"), MatchEvent(45, 2, "a"), MatchEvent(90, 0, "c"))
            .sortedWith(compareBy({ it.minute }, { it.added }))
        assertEquals(listOf("45+2′", "90′", "90+3′"), events.map { it.clock })
        val live = sampleMatches.first { it.status.contains("进行") }
        val liveEvents = demoEvents(live)
        assertEquals(1, liveEvents.count { it.label == "${live.home} 进球" })
        assertEquals(1, liveEvents.count { it.label == "${live.away} 进球" })
        assertTrue(demoEvents(sampleMatches.first().copy(status = "演示延期", eventsAvailable = false)).isEmpty())
    }
    @Test fun failuresGiveActionableAndDistinctMessages() {
        assertTrue(readableError(SocketTimeoutException()).contains("超时"))
        assertTrue(readableError(UnknownHostException()).contains("网络"))
        assertTrue(readableError(SportsHttpException(429)).contains("频繁"))
        assertTrue(readableError(SportsHttpException(500)).contains("不可用"))
    }
}
