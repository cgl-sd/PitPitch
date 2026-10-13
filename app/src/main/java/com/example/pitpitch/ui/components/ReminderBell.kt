package com.example.pitpitch.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pitpitch.data.reminder.MatchReminders
import com.example.pitpitch.data.reminder.ReminderBooking
import com.example.pitpitch.ui.FootballGreen
import com.example.pitpitch.ui.Muted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun ReminderBell(booking: ReminderBooking, accent: Color = FootballGreen) {
    val context = LocalContext.current
    val reminders = remember(context.applicationContext) { MatchReminders.get(context) }
    val state by reminders.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val booked = state.subscriptions.containsKey(booking.key)
    var busy by remember(booking.key) { mutableStateOf(false) }
    // Only a user click creates this pending intent. ActivityResultRegistry delivers the
    // permission/settings result after recreation; the saved key keeps it tied to this match.
    var pendingKey by rememberSaveable(booking.key) { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable(booking.key) { mutableIntStateOf(0) }
    var notificationRequested by rememberSaveable(booking.key) { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    fun clearPending() {
        pendingKey = null
        pendingAction = 0
    }
    fun timely(): Boolean {
        val start = booking.start
        if (start == null) {
            toast("尚未提供开赛时间，无法预约提醒。")
            return false
        }
        if (!start.minusSeconds(5 * 60).isAfter(Instant.now())) {
            toast("开赛前5分钟的提醒时间已过，未创建预约。")
            return false
        }
        return true
    }
    fun subscribe() {
        if (pendingKey != booking.key) return
        clearPending()
        if (!timely()) return
        if (!reminders.canNotify()) {
            toast("未开启比赛提醒通知，预约未保存。")
            return
        }
        if (!reminders.canScheduleExact()) {
            toast("未允许准时提醒，预约未保存。")
            return
        }
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                reminders.subscribe(booking)
                toast("已预约")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                toast(failure.message ?: "预约未保存，请重试。")
            } finally {
                busy = false
            }
        }
    }

    val exactPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingKey == booking.key && pendingAction == 3) {
            // RESULT_OK is not the grant: this special permission must be queried again.
            subscribe()
        }
    }
    fun exactOrSubscribe() {
        if (pendingKey != booking.key) return
        if (!timely()) {
            clearPending()
            return
        }
        if (reminders.canScheduleExact()) {
            subscribe()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingAction = 3
            toast("请允许闹钟和提醒，才能在开赛前5分钟准时通知。")
            try {
                exactPermission.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) {
                clearPending()
                toast("无法打开准时提醒设置，预约未保存。")
            }
        } else {
            clearPending()
            toast("系统未允许准时提醒，预约未保存。")
        }
    }
    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingKey == booking.key && pendingAction == 2) {
            if (reminders.canNotify()) exactOrSubscribe()
            else {
                clearPending()
                toast("未开启比赛提醒通知，预约未保存。")
            }
        }
    }
    fun openNotificationSettings() {
        if (pendingKey != booking.key) return
        pendingAction = 2
        toast("比赛提醒通知已关闭，请在通知设置中开启；开启后才能保存预约。")
        try {
            notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } catch (_: Exception) {
            clearPending()
            toast("无法打开通知设置，预约未保存。")
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingKey == booking.key && pendingAction == 1) {
            if (reminders.canNotify()) exactOrSubscribe()
            else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                openNotificationSettings()
            }
            else {
                clearPending()
                toast("未获得通知权限或比赛提醒通知已关闭，预约未保存。")
            }
        }
    }

    IconButton(onClick = {
        if (busy || pendingKey != null) return@IconButton
        if (booked) {
            // Cancellation is always allowed, including expired matches and revoked grants.
            clearPending()
            busy = true
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    if (reminders.cancel(booking.key)) toast("已取消")
                    else toast("此比赛目前没有预约。")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    toast(failure.message ?: "取消失败，请重试。")
                } finally {
                    busy = false
                }
            }
        } else if (!state.ready) {
            toast(state.error ?: "正在读取比赛提醒，请稍后重试。")
        } else if (timely()) {
            pendingKey = booking.key
            if (reminders.canNotify()) {
                exactOrSubscribe()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (!notificationRequested || (context as? Activity)?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
                } == true)) {
                notificationRequested = true
                pendingAction = 1
                try {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Exception) {
                    clearPending()
                    toast("无法请求通知权限，预约未保存。")
                }
            } else {
                openNotificationSettings()
            }
        }
    }, enabled = !busy && pendingKey == null,
        modifier = Modifier.size(48.dp).testTag("reminder-${booking.key}").semantics { selected = booked }) {
        Icon(if (booked) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone,
            if (booked) "已预约，开赛前5分钟提醒；点击取消" else "预约开赛前5分钟提醒",
            Modifier.size(20.dp), tint = if (booked) accent else Muted.copy(alpha = if (booking.available()) .55f else .25f))
    }
}
