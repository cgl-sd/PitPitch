package com.example.pitpitch

import com.example.pitpitch.data.reminder.ReminderBooking
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BookingTest {
    @Test fun onlyPublishedFutureUtcTimesCanBeBooked() {
        val now = Instant.parse("2026-10-11T10:00:00Z")
        fun booking(time: String?) = ReminderBooking("source-id", "Published match", time, "Venue", "ESPN")
        assertFalse(booking(null).available(now))
        assertFalse(booking("未提供").available(now))
        assertFalse(booking("2026-10-11T10:00:00Z").available(now))
        assertFalse(booking("2026-10-10T10:00:00Z").available(now))
        val future = booking("2026-10-11T11:00:00Z")
        assertTrue(future.available(now))
        assertEquals(Instant.parse("2026-10-11T11:00:00Z"), future.start)
    }
}
