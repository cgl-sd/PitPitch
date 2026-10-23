package com.example.pitpitch.data.reminder

import java.time.Instant

data class ReminderBooking(
    val key: String,
    val title: String,
    val startUtc: String?,
    val location: String,
    val source: String,
) {
    val start: Instant? = startUtc?.let { runCatching { Instant.parse(it) }.getOrNull() }
    fun available(now: Instant = Instant.now()): Boolean = start?.isAfter(now) == true
}

data class ReminderEntry(
    val booking: ReminderBooking,
    val triggerAt: Long,
    val token: String,
    val delivered: Boolean = false,
)

data class ReminderState(
    val subscriptions: Map<String, ReminderEntry>,
    val ready: Boolean,
    val error: String?,
)

internal const val REMINDER_LEAD_SECONDS = 5 * 60L

internal fun ReminderBooking.reminderAt(now: Instant): Long {
    val kickoff = requireNotNull(start) { "赛程尚未提供有效的开赛时间" }
    val trigger = kickoff.minusSeconds(REMINDER_LEAD_SECONDS)
    require(trigger.isAfter(now)) { "距离开赛不足 5 分钟，无法预约提醒" }
    return trigger.toEpochMilli().also {
        require(Instant.ofEpochMilli(it).isAfter(now)) { "距离开赛不足 5 分钟，无法预约提醒" }
    }
}
