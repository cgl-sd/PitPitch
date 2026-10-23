package com.example.pitpitch.data.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        receiverScope.launch {
            try {
                MatchReminders.get(app).handleAlarm(intent)
            } catch (_: Exception) {
                // MatchReminders exposes the actual failure in state and preserves the durable record.
            } finally {
                pending.finish()
            }
        }
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in restoreActions) return
        val pending = goAsync()
        val app = context.applicationContext
        receiverScope.launch {
            try {
                MatchReminders.get(app).restore()
            } catch (_: Exception) {
                // Corrupt/inaccessible facts remain untouched and the application can display their error.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val restoreActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
