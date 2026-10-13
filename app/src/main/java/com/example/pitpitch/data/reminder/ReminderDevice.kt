package com.example.pitpitch.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.pitpitch.MainActivity
import com.example.pitpitch.R
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal open class ReminderDevice(
    context: Context,
    internal val namespace: String = "matches",
    private val channelId: String = CHANNEL_ID,
    private val alarmAction: String = ALARM_ACTION,
) {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    private fun channel(): NotificationChannel {
        notifications.getNotificationChannel(channelId)?.let { return it }
        notifications.createNotificationChannel(NotificationChannel(channelId, "赛事预约提醒", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "在已预约赛事的实际开赛时间前 5 分钟提醒" })
        return notifications.getNotificationChannel(channelId) ?: throw IOException("无法创建赛事提醒通知渠道")
    }

    open fun canNotify(): Boolean = try {
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() && channel().importance != NotificationManager.IMPORTANCE_NONE
    } catch (_: RuntimeException) {
        false
    } catch (_: IOException) {
        false
    }

    open fun canScheduleExact(): Boolean = try {
        Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    } catch (_: RuntimeException) {
        false
    }

    internal fun alarmIntent(entry: ReminderEntry): Intent = Intent(app, ReminderAlarmReceiver::class.java)
        .setAction(alarmAction)
        .setData(Uri.Builder().scheme(URI_SCHEME).authority(namespace).appendPath("alarm")
            .appendPath(entry.booking.key).appendPath(entry.token).build())

    internal fun alarmIdentity(intent: Intent): Pair<String, String>? {
        val uri = intent.data ?: return null
        val paths = uri.pathSegments
        if (intent.action != alarmAction || uri.scheme != URI_SCHEME || uri.authority != namespace ||
            paths.size != 3 || paths[0] != "alarm") return null
        return paths[1] to paths[2]
    }

    private fun alarmPending(entry: ReminderEntry, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(app, 0, alarmIntent(entry), flags or PendingIntent.FLAG_IMMUTABLE)

    open fun schedule(entry: ReminderEntry) {
        if (!canScheduleExact()) throw SecurityException("请允许 PitPitch 设置精确闹钟")
        val pending = requireNotNull(alarmPending(entry, PendingIntent.FLAG_UPDATE_CURRENT))
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAt, pending)
    }

    open fun cancelAlarm(entry: ReminderEntry) {
        val pending = alarmPending(entry, PendingIntent.FLAG_NO_CREATE) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    internal fun notificationTag(key: String): String = Uri.Builder().scheme(URI_SCHEME).authority(namespace)
        .appendPath("notification").appendPath(key).build().toString()

    open fun cancelNotification(entry: ReminderEntry) {
        notifications.cancel(notificationTag(entry.booking.key), NOTIFICATION_ID)
        val deadline = android.os.SystemClock.elapsedRealtime() + 500
        while (notificationPresent(entry) && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(10)
        }
        if (notificationPresent(entry)) throw IOException("无法取消已投递的赛事提醒通知")
    }

    open fun notificationPresent(entry: ReminderEntry): Boolean = notifications.activeNotifications.any {
        it.tag == notificationTag(entry.booking.key) && it.id == NOTIFICATION_ID &&
            it.notification.extras.getString(TOKEN_EXTRA) == entry.token
    }

    open fun post(entry: ReminderEntry) {
        if (!canNotify()) throw SecurityException("请允许 PitPitch 发送赛事提醒通知")
        val booking = entry.booking
        val kickoff = requireNotNull(booking.start)
        val localTime = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm z", Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.systemDefault()).format(kickoff)
        val text = "开赛时间：$localTime" + booking.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val click = Intent(app, MainActivity::class.java).setData(Uri.Builder().scheme(URI_SCHEME)
            .authority(namespace).appendPath("open").appendPath(booking.key).appendPath(entry.token).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(app, 0, click, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, channelId)
            .setSmallIcon(R.drawable.ic_match_reminder)
            .setContentTitle(booking.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setWhen(entry.triggerAt)
            .setShowWhen(true)
            .addExtras(android.os.Bundle().apply { putString(TOKEN_EXTRA, entry.token) })
            .build()
        notifications.notify(notificationTag(booking.key), NOTIFICATION_ID, notification)
        // NotificationManager can silently drop a post if permission/channel changed while it was built.
        // Android enqueues notify() asynchronously; observe that single post, without issuing another one.
        val deadline = android.os.SystemClock.elapsedRealtime() + 500
        while (!notificationPresent(entry) && canNotify() && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(10)
        }
        if (!canNotify() || !notificationPresent(entry)) throw IOException("赛事提醒通知未成功投递")
    }

    companion object {
        internal const val CHANNEL_ID = "match_reminders"
        internal const val ALARM_ACTION = "com.example.pitpitch.action.MATCH_REMINDER"
        internal const val URI_SCHEME = "pitpitch-reminder"
        internal const val TOKEN_EXTRA = "pitpitch_reminder_token"
        internal const val NOTIFICATION_ID = 1
    }
}
