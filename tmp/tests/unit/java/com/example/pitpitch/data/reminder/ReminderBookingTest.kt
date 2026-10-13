package com.example.pitpitch.data.reminder

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReminderBookingTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private fun booking(start: String?) = ReminderBooking("source/key", "曼联 vs 阿森纳", start, "Old Trafford", "ESPN")

    @Test fun availabilityMeansPublishedFutureKickoffNotCalendarOrReminderPermission() {
        assertFalse(booking(null).available(now))
        assertFalse(booking("未提供").available(now))
        assertFalse(booking(now.toString()).available(now))
        assertFalse(booking(now.minusSeconds(1).toString()).available(now))
        assertTrue(booking(now.plusSeconds(1).toString()).available(now))
    }

    @Test fun exactFiveMinuteBoundaryCannotBeRegisteredButNextMillisecondCan() {
        for (kickoff in listOf(now, now.plusSeconds(299), now.plusSeconds(300))) {
            try {
                booking(kickoff.toString()).reminderAt(now)
                fail("The five-minute alarm must still be in the future")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(now.plusMillis(1).toEpochMilli(), booking(now.plusSeconds(300).plusMillis(1).toString()).reminderAt(now))
        // AlarmManager works in milliseconds: a fractional instant that rounds down to now is not schedulable.
        try {
            booking(now.plusSeconds(300).plusNanos(1).toString()).reminderAt(now)
            fail("A trigger that rounds down to now must not report success")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun originalSourceUtcAndStableSourceKeyAreNeverReformattedOrReplaced() {
        val sourceUtc = "2026-10-13T13:04:05.123456Z"
        val value = booking(sourceUtc)
        assertEquals(sourceUtc, value.startUtc)
        assertEquals("source/key", value.key)
        assertEquals(Instant.parse(sourceUtc), value.start)
        assertEquals(Instant.parse(sourceUtc).minusSeconds(300).toEpochMilli(), value.reminderAt(now))
    }

    @Test fun reminderEntryRetainsSubscribedMatchAndDeliveryDoesNotRemoveItsKey() {
        val value = booking("2026-10-13T13:00:00Z")
        val entry = ReminderEntry(value, value.reminderAt(now), "token")
        val state = ReminderState(mapOf(value.key to entry.copy(delivered = true)), true, null)
        assertEquals(value, state.subscriptions[value.key]!!.booking)
        assertTrue(state.subscriptions[value.key]!!.delivered)
    }
}
