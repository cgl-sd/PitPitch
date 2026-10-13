package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class F1CalendarFocusTest {
    @Test fun currentDayNextRaceCompletedRaceAndHistoricalSeasonHaveStableFocus() {
        val races = listOf(
            F1Race(2026, 1, "One", "One", "2026-10-11", "14:00:00Z", "UK"),
            F1Race(2026, 2, "Two", "Two", "2026-10-12", "14:00:00Z", "UK"),
            F1Race(2026, 3, "Three", "Three", "2026-10-25", null, "UK"))
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(races[1], f1CalendarFocus(races, Instant.parse("2026-10-12T14:30:00Z"), zone))
        assertEquals(races[2], f1CalendarFocus(races, Instant.parse("2026-10-12T14:30:00Z"), zone, setOf(races[1].id)))
        assertEquals(races[0], f1CalendarFocus(races, Instant.parse("2026-11-01T00:00:00Z"), zone))
        assertEquals(races[0], f1CalendarFocus(races, Instant.parse("2026-10-01T00:00:00Z"), zone))
        assertNull(f1CalendarFocus(emptyList(), Instant.now(), zone))
    }
    @Test fun historicalFocusDoesNotBecomeAnExpandableRace() {
        val zone = ZoneId.of("Asia/Shanghai")
        val races = listOf(
            F1Race(2025, 1, "One", "One", "2025-03-16", "04:00:00Z", "AU"),
            F1Race(2025, 24, "Abu Dhabi", "Yas Marina", "2025-12-07", "13:00:00Z", "AE"))
        val now = Instant.parse("2026-10-12T12:00:00Z")
        assertEquals(races.first(), f1CalendarFocus(races, now, zone))
        assertNull(f1UpcomingRace(races, now, zone))
    }

    @Test fun expandableRaceUsesDeviceDayAndExcludesPublishedResultsWithoutSortingInput() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-12T01:00:00Z")
        val today = F1Race(2026, 2, "Today", "Today", "2026-10-11", "20:00:00Z", "US")
        val past = F1Race(2026, 1, "Past", "Past", "2026-10-10", null, "US")
        val future = F1Race(2026, 3, "Next", "Next", "2026-10-25", null, "US")
        val invalid = F1Race(2026, 0, "Unknown", "Unknown", "unknown", null, "")
        val races = listOf(future, invalid, today, past)
        assertEquals(today, f1UpcomingRace(races, now, zone))
        assertEquals(future, f1UpcomingRace(races, now, zone, setOf(today.id)))
        assertNull(f1UpcomingRace(races, now, zone, setOf(today.id, future.id)))
    }
}
