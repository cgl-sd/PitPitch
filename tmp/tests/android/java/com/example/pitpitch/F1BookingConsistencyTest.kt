package com.example.pitpitch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.F1Race
import com.example.pitpitch.data.reminder.MatchReminders
import com.example.pitpitch.data.reminder.ReminderAlarmReceiver
import com.example.pitpitch.data.reminder.ReminderBooking
import com.example.pitpitch.data.reminder.ReminderEntry
import com.example.pitpitch.feature.F1Screen
import com.example.pitpitch.feature.RaceDetail
import com.example.pitpitch.feature.RaceWeekendCard
import com.example.pitpitch.ui.SportsColors
import com.example.pitpitch.ui.components.ReminderBell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class F1BookingConsistencyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun reminders(): MatchReminders = MatchReminders.get(rule.activity).also {
        runBlocking { it.restore() }
        assertTrue("Enable this app's notifications through the actual system UI before this test", it.canNotify())
        assertTrue("Allow this app's alarms and reminders through the actual system UI before this test", it.canScheduleExact())
        assertTrue("Reminder storage must be ready", it.state.value.ready)
    }

    private fun alarm(context: Context, entry: ReminderEntry): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = "com.example.pitpitch.action.MATCH_REMINDER"
            data = Uri.Builder().scheme("pitpitch-reminder").authority("matches")
                .appendPath("alarm").appendPath(entry.booking.key).appendPath(entry.token).build()
        }, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun awaitBooked(manager: MatchReminders, key: String): ReminderEntry {
        rule.waitUntil(10000) { manager.state.value.subscriptions.containsKey(key) }
        rule.onNodeWithTag("reminder-$key").assertIsSelected()
        return manager.state.value.subscriptions.getValue(key)
    }

    private fun awaitCancelled(manager: MatchReminders, entry: ReminderEntry) {
        rule.waitUntil(10000) { !manager.state.value.subscriptions.containsKey(entry.booking.key) }
        rule.onNodeWithTag("reminder-${entry.booking.key}").assertIsNotSelected()
        assertNull("The cancelled alarm token must be removed", alarm(rule.activity, entry))
    }

    @Test fun homepageCalendarAndDetailShareOneApplicationReminderAndCancelEachOther() {
        val manager = reminders()
        val page = mutableIntStateOf(0)
        val start = Instant.now().plusSeconds(10 * 86400).atZone(ZoneId.of("UTC"))
        val round = UUID.randomUUID().hashCode().and(Int.MAX_VALUE).coerceAtLeast(100)
        val race = F1Race(start.year, round, "Reminder Validation Grand Prix", "Validation Circuit",
            start.toLocalDate().toString(), "14:00:00Z", "Bahrain")
        val zone = ZoneId.of("UTC")
        try {
            rule.setContent {
                MaterialTheme(colorScheme = SportsColors) {
                    when (page.intValue) {
                        0 -> RaceWeekendCard(race, zone, homePreview = true) {}
                        1 -> F1Screen(SportsViewModel.State(season = race.season,
                            calendars = mapOf(race.season to listOf(race))), zone, {}, {}, {}, {})
                        2 -> RaceDetail(race, zone, null, {}, null, false)
                    }
                }
            }
            rule.onNodeWithTag("reminder-${race.id}").assertIsNotSelected().performClick()
            val first = awaitBooked(manager, race.id)
            assertNotNull("Booking must create an alarm PendingIntent", alarm(rule.activity, first))
            assertEquals(Instant.parse("${race.date}T${race.utcTime}").minusSeconds(300).toEpochMilli(), first.triggerAt)
            assertFalse(manager.state.value.subscriptions.containsKey("${race.id}-正赛"))

            rule.runOnUiThread { page.intValue = 1 }
            rule.onNodeWithTag("reminder-${race.id}").assertIsSelected().performClick()
            awaitCancelled(manager, first)

            rule.runOnUiThread { page.intValue = 2 }
            rule.onNodeWithTag("reminder-${race.id}").assertIsNotSelected().performClick()
            val second = awaitBooked(manager, race.id)
            assertNotEquals(first.token, second.token)
            assertNotNull(alarm(rule.activity, second))
            assertFalse(manager.state.value.subscriptions.containsKey("${race.id}-正赛"))

            rule.runOnUiThread { page.intValue = 0 }
            rule.onNodeWithTag("reminder-${race.id}").assertIsSelected().performClick()
            awaitCancelled(manager, second)
        } finally {
            runBlocking { manager.cancel(race.id) }
        }
    }

    @Test fun actualAlarmDeliversSubscribedMatchNotificationAndBellCancelsAfterKickoff() {
        val manager = reminders()
        val page = mutableIntStateOf(0)
        val key = "reminder-validation-${UUID.randomUUID()}"
        val title = "Subscribed match ${UUID.randomUUID()}"
        val start = Instant.now().plusSeconds(330)
        val booking = ReminderBooking(key, title, start.toString(), "Validation venue", "UI validation")
        val notifications = rule.activity.getSystemService(NotificationManager::class.java)
        try {
            rule.setContent {
                MaterialTheme(colorScheme = SportsColors) {
                    // The changed start simulates an updated feed that now reports a past kickoff.
                    ReminderBell(if (page.intValue == 0) booking else booking.copy(startUtc = Instant.now().minusSeconds(1).toString()))
                }
            }
            rule.onNodeWithTag("reminder-$key").assertIsNotSelected().performClick()
            val entry = awaitBooked(manager, key)
            assertEquals(start.minusSeconds(300).toEpochMilli(), entry.triggerAt)
            assertNotNull(alarm(rule.activity, entry))
            rule.waitUntil(60000) {
                notifications.activeNotifications.any {
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains(title) == true ||
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains(title) == true ||
                        it.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.contains(title) == true
                }
            }
            rule.waitUntil(10000) { manager.state.value.subscriptions[key]?.delivered == true }
            rule.runOnUiThread { page.intValue = 1 }
            rule.onNodeWithTag("reminder-$key").assertIsSelected().performClick()
            awaitCancelled(manager, entry)
            rule.waitUntil(10000) {
                notifications.activeNotifications.none {
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains(title) == true ||
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains(title) == true ||
                        it.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.contains(title) == true
                }
            }
        } finally {
            runBlocking { manager.cancel(key) }
        }
    }
}
