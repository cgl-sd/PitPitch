package com.example.pitpitch.data.reminder

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

class MatchReminders internal constructor(
    private val store: ReminderStore,
    private val device: ReminderDevice,
    private val now: () -> Instant = { Instant.now() },
) {
    private val lock = Mutex()
    private var loaded = false
    private var records: Map<String, StoredReminder> = emptyMap()
    private val mutableState = MutableStateFlow(ReminderState(emptyMap(), false, null))
    val state: StateFlow<ReminderState> = mutableState.asStateFlow()

    fun canNotify(): Boolean = device.canNotify()
    fun canScheduleExact(): Boolean = device.canScheduleExact()

    suspend fun subscribe(booking: ReminderBooking): Unit = locked {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            ReminderStore.validateBooking(booking)
            requirePermissions()
            val triggerAt = booking.reminderAt(now())
            val existing = records[booking.key]
            if (existing != null) {
                if (existing.stage == ReminderStage.CANCELING || existing.stage == ReminderStage.REGISTERING) {
                    remove(existing)
                } else {
                    publish()
                    return@withContext
                }
            }
            val entry = ReminderEntry(booking, triggerAt, UUID.randomUUID().toString())
            // An interrupted registration is not a subscription. Recovery cancels it rather than inventing success.
            persist(records + (booking.key to StoredReminder(entry, ReminderStage.REGISTERING)))
            try {
                device.schedule(entry)
                requirePermissions()
                require(Instant.ofEpochMilli(triggerAt).isAfter(now())) { "距离开赛不足 5 分钟，无法预约提醒" }
                persist(records + (booking.key to StoredReminder(entry, ReminderStage.ACTIVE)))
                requirePermissions()
                require(Instant.ofEpochMilli(triggerAt).isAfter(now())) { "距离开赛不足 5 分钟，无法预约提醒" }
                publish()
            } catch (error: Exception) {
                try {
                    remove(requireNotNull(records[booking.key]))
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }

    /** A successful return means both the durable token and its OS alarm/notification are gone. */
    suspend fun cancel(key: String): Boolean = locked {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val record = records[key] ?: return@withContext false
            remove(record)
            publish()
            true
        }
    }

    suspend fun restore(): Unit = locked {
        var recoveryError: String? = null
        for (key in records.keys) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val record = records[key] ?: return@withContext
                try {
                    when {
                        record.stage == ReminderStage.REGISTERING || record.stage == ReminderStage.CANCELING -> remove(record)
                        record.entry.booking.start?.isAfter(now()) != true -> remove(record)
                        record.stage == ReminderStage.POSTING -> {
                            device.cancelAlarm(record.entry)
                            if (device.notificationPresent(record.entry)) {
                                persist(records + (key to StoredReminder(record.entry.copy(delivered = true), ReminderStage.ACTIVE)))
                            } else {
                                // A crash may have happened either side of notify(), or the user may have dismissed it.
                                // Keep the fact cancellable, but never claim delivery or emit a second notification.
                                recoveryError = recoveryError ?: "赛事提醒投递曾被中断，未重复发送；可取消后重新预约"
                            }
                        }
                        !record.entry.delivered -> {
                            requirePermissions()
                            // Always use the original UTC-derived trigger, including OS-delayed pre-kickoff alarms.
                            device.schedule(record.entry)
                        }
                    }
                } catch (error: Exception) {
                    recoveryError = recoveryError ?: message(error)
                }
            }
        }
        publish(recoveryError)
    }

    internal suspend fun handleAlarm(intent: Intent): Unit = locked {
        val (key, token) = device.alarmIdentity(intent) ?: return@locked
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val record = records[key] ?: return@withContext
            val entry = record.entry
            if (entry.token != token || record.stage != ReminderStage.ACTIVE || entry.delivered) return@withContext
            val instant = now()
            if (entry.booking.start?.isAfter(instant) != true) {
                remove(record)
                publish()
                return@withContext
            }
            if (Instant.ofEpochMilli(entry.triggerAt).isAfter(instant)) {
                requirePermissions()
                device.schedule(entry)
                return@withContext
            }
            requirePermissions()
            persist(records + (key to StoredReminder(entry, ReminderStage.POSTING)))
            try {
                require(entry.booking.start?.isAfter(now()) == true) { "赛事已开赛，不再发送过期提醒" }
                device.post(entry)
            } catch (error: Exception) {
                try {
                    device.cancelNotification(entry)
                    persist(records + (key to StoredReminder(entry, ReminderStage.ACTIVE)))
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
            // POSTING remains recoverable if this commit fails after the genuine OS notification was posted.
            persist(records + (key to StoredReminder(entry.copy(delivered = true), ReminderStage.ACTIVE)))
            device.cancelAlarm(entry)
            publish()
        }
    }

    private fun requirePermissions() {
        if (!canNotify()) throw SecurityException("请允许 PitPitch 发送赛事提醒通知，并开启赛事预约提醒渠道")
        if (!canScheduleExact()) throw SecurityException("请允许 PitPitch 设置精确闹钟")
    }

    private fun load() {
        if (loaded) return
        records = store.read()
        loaded = true
        publish()
    }

    private fun persist(next: Map<String, StoredReminder>) {
        try {
            store.save(next)
            records = next
            loaded = true
        } catch (error: Exception) {
            loaded = false
            throw error
        }
    }

    private fun remove(record: StoredReminder) {
        val key = record.entry.booking.key
        if (record.stage != ReminderStage.CANCELING) {
            persist(records + (key to record.copy(stage = ReminderStage.CANCELING)))
        }
        // The durable cancellation tombstone is visible to any late receiver before either OS operation.
        publish()
        device.cancelAlarm(record.entry)
        device.cancelNotification(record.entry)
        persist(records - key)
    }

    private fun publish(error: String? = null) {
        val subscriptions = buildMap {
            records.forEach { (key, record) ->
                if (record.stage == ReminderStage.ACTIVE || record.stage == ReminderStage.POSTING) put(key, record.entry)
            }
        }
        mutableState.value = ReminderState(subscriptions, loaded, error)
    }

    private suspend fun <T> locked(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                load()
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publish(message(error))
                throw error
            }
        }
    }

    private fun message(error: Exception): String = error.message?.takeIf { it.isNotBlank() } ?: "赛事提醒操作失败"

    companion object {
        @Volatile private var shared: MatchReminders? = null

        fun get(context: Context): MatchReminders = shared ?: synchronized(this) {
            shared ?: context.applicationContext.let { app ->
                MatchReminders(ReminderStore { File(app.noBackupFilesDir, "match-reminders.json") }, ReminderDevice(app))
            }.also { shared = it }
        }
    }
}
