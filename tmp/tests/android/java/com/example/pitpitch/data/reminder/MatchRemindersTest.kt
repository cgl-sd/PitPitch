package com.example.pitpitch.data.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android alarms/notifications, but every file, action, URI and channel belongs only to its fixture. */
class MatchRemindersTest {
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val namespace = "reminder-test-${UUID.randomUUID()}"
        val channelId = namespace
        val action = "${ReminderDevice.ALARM_ACTION}.$namespace"
        val directory = File(context.noBackupFilesDir, namespace).apply { check(mkdirs()) }
        val file = File(directory, "records.json")
        val store = ReminderStore(file)
        val device = ReminderDevice(context, namespace, channelId, action)
        val notifications = context.getSystemService(NotificationManager::class.java)
        var clock: Instant = Instant.now()
        val engine = fresh()
        val entries = mutableListOf<ReminderEntry>()

        fun fresh(store: ReminderStore = this.store, device: ReminderDevice = this.device) = MatchReminders(store, device) { clock }
        fun booking(key: String = "match/源数据/Aa"): ReminderBooking = ReminderBooking(key, "曼联 vs 阿森纳 · $namespace",
            clock.plusSeconds(3600).toString(), "Old Trafford", "ESPN")
        fun requirePlatformPermissions() {
            assertTrue("Grant POST_NOTIFICATIONS before native reminder instrumentation", device.canNotify())
            assertTrue("Allow SCHEDULE_EXACT_ALARM before native reminder instrumentation", device.canScheduleExact())
        }
        suspend fun subscribe(booking: ReminderBooking = booking(), engine: MatchReminders = this.engine): ReminderEntry {
            engine.subscribe(booking)
            return requireNotNull(engine.state.value.subscriptions[booking.key]).also { entries += it }
        }
        fun pending(entry: ReminderEntry): PendingIntent? = PendingIntent.getBroadcast(context, 0, device.alarmIntent(entry),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        fun posted(entry: ReminderEntry) = notifications.activeNotifications.singleOrNull {
            it.tag == device.notificationTag(entry.booking.key) && it.id == ReminderDevice.NOTIFICATION_ID
        }
        fun alarms(): String {
            val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys alarm").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            // Cancellation history and aggregate statistics still name alarms that no longer exist.
            val end = listOf("  Removal history:", "  Top Alarms:", "  Alarm Stats:")
                .map { dump.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: dump.length
            return dump.substring(0, end)
        }
        fun close() {
            // Never clear application data, cancel other alarms, revoke app-wide permissions or touch user channels.
            val saved = runCatching { store.read().values.map { it.entry } }.getOrDefault(emptyList())
            (entries + saved).distinctBy { it.token }.forEach {
                device.cancelAlarm(it)
                device.cancelNotification(it)
            }
            notifications.deleteNotificationChannel(channelId)
            check(directory.deleteRecursively())
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private suspend fun fails(action: suspend () -> Unit) {
        try { action(); fail("Operation must not report success") } catch (_: IOException) { }
        catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
    }

    @Test fun realExactAlarmRegistrationCancellationAndFreshProcessRestoreKeepOriginalUtc() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val entry = f.subscribe(f.booking().copy(startUtc = f.clock.plusSeconds(3600).plusNanos(123456000).toString()))
            assertNotNull(f.pending(entry))
            assertTrue("AlarmManager must contain this fixture's registered alarm", f.alarms().contains(f.action))
            assertEquals(entry, f.store.read()[entry.booking.key]!!.entry)
            // Simulate loss of OS alarms at reboot, then construct a new process-local engine from only durable facts.
            f.device.cancelAlarm(entry)
            assertNull(f.pending(entry))
            assertFalse(f.alarms().contains(f.action))
            val restarted = f.fresh()
            restarted.restore()
            assertEquals(entry, restarted.state.value.subscriptions[entry.booking.key])
            assertEquals(entry.booking.start!!.minusSeconds(300).toEpochMilli(), entry.triggerAt)
            assertNotNull(f.pending(entry))
            assertTrue(f.alarms().contains(f.action))
            assertTrue(restarted.cancel(entry.booking.key))
            assertNull(f.pending(entry))
            assertFalse(f.alarms().contains(f.action))
            assertTrue(f.fresh().also { it.restore() }.state.value.subscriptions.isEmpty())
            assertFalse(restarted.cancel(entry.booking.key))
        }
    }

    @Test fun delayedLoopbackPostsActualSubscribedTitleKickoffAndTokenThenNeverDuplicates() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val entry = f.subscribe()
            f.clock = entry.booking.start!!.minusSeconds(90)
            f.engine.handleAlarm(f.device.alarmIntent(entry))
            val posted = requireNotNull(f.posted(entry))
            assertEquals(entry.booking.title, posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val expectedKickoff = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm z", Locale.SIMPLIFIED_CHINESE)
                .withZone(ZoneId.systemDefault()).format(entry.booking.start)
            val text = posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertTrue(text.contains(expectedKickoff))
            assertFalse(text.contains("5 分钟"))
            assertEquals(entry.token, posted.notification.extras.getString(ReminderDevice.TOKEN_EXTRA))
            assertTrue(f.engine.state.value.subscriptions[entry.booking.key]!!.delivered)
            assertNull(f.pending(entry))
            // Dismissal and reboot/process restoration must not turn a delivered entry into another notification.
            f.device.cancelNotification(entry)
            val restarted = f.fresh()
            restarted.restore()
            restarted.handleAlarm(f.device.alarmIntent(entry))
            assertNull(f.posted(entry))
            assertTrue(restarted.state.value.subscriptions[entry.booking.key]!!.delivered)
            assertTrue(restarted.cancel(entry.booking.key))
            assertTrue(f.store.read().isEmpty())
        }
    }

    @Test fun staleTokenAfterCancelAndRebookCannotPostAndHashCollidingKeysStayIndependent() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val first = f.subscribe(f.booking("Aa"))
            val collision = f.subscribe(f.booking("BB"))
            assertEquals("Aa".hashCode(), "BB".hashCode())
            assertTrue(f.engine.cancel(first.booking.key))
            assertNull(f.pending(first))
            assertNotNull(f.pending(collision))
            val replacement = f.subscribe(first.booking)
            assertNotEquals(first.token, replacement.token)
            assertNotEquals(f.device.alarmIntent(first).data, f.device.alarmIntent(replacement).data)
            f.clock = first.booking.start!!.minusSeconds(60)
            f.engine.handleAlarm(f.device.alarmIntent(first))
            assertNull(f.posted(first))
            assertFalse(f.engine.state.value.subscriptions[replacement.booking.key]!!.delivered)
            f.engine.handleAlarm(f.device.alarmIntent(replacement))
            assertEquals(replacement.token, f.posted(replacement)!!.notification.extras.getString(ReminderDevice.TOKEN_EXTRA))
            assertTrue(f.engine.cancel(replacement.booking.key))
            f.engine.handleAlarm(f.device.alarmIntent(replacement))
            assertNull(f.posted(replacement))
        }
    }

    @Test fun cancellationRacingRealNotificationCommitFinishesWithoutAnyNotification() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val controlled = object : ReminderDevice(f.context, f.namespace, f.channelId, f.action) {
                override fun post(entry: ReminderEntry) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    super.post(entry)
                }
            }
            val engine = f.fresh(device = controlled)
            val entry = f.subscribe(engine = engine)
            f.clock = entry.booking.start!!.minusSeconds(60)
            coroutineScope {
                val delivery = async(start = CoroutineStart.UNDISPATCHED) { engine.handleAlarm(controlled.alarmIntent(entry)) }
                try {
                    // The engine dispatches to IO; this wait cannot block the actual receiver commit thread.
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    val cancelStarted = CompletableDeferred<Unit>()
                    val cancel = async { cancelStarted.complete(Unit); engine.cancel(entry.booking.key) }
                    cancelStarted.await()
                    release.countDown()
                    delivery.await()
                    assertTrue(cancel.await())
                } finally { release.countDown() }
            }
            assertFalse(engine.state.value.subscriptions.containsKey(entry.booking.key))
            assertNull(f.posted(entry))
            assertNull(f.pending(entry))
            assertTrue(f.store.read().isEmpty())
            engine.handleAlarm(controlled.alarmIntent(entry))
            assertNull(f.posted(entry))
        }
    }

    @Test fun blockedOwnChannelAndDeniedExactPermissionNeverCreateSubscription() = runBlocking {
        fixture { f ->
            f.notifications.createNotificationChannel(NotificationChannel(f.channelId, "Blocked reminder test", NotificationManager.IMPORTANCE_NONE))
            assertFalse(f.device.canNotify())
            fails { f.engine.subscribe(f.booking()) }
            assertTrue(f.engine.state.value.subscriptions.isEmpty())
            assertTrue(f.store.read().isEmpty())
            assertNotNull(f.engine.state.value.error)
        }
        fixture { f ->
            f.requirePlatformPermissions()
            val deniedExact = object : ReminderDevice(f.context, f.namespace, f.channelId, f.action) {
                override fun canScheduleExact() = false
            }
            val engine = f.fresh(device = deniedExact)
            fails { engine.subscribe(f.booking()) }
            assertTrue(engine.state.value.subscriptions.isEmpty())
            assertTrue(f.store.read().isEmpty())
            assertFalse(f.alarms().contains(f.action))
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) fixture { f ->
            f.requirePlatformPermissions()
            val deniedContext = object : android.content.ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (permission == android.Manifest.permission.POST_NOTIFICATIONS) android.content.pm.PackageManager.PERMISSION_DENIED
                    else super.checkPermission(permission, pid, uid)
            }
            val deniedNotification = ReminderDevice(deniedContext, f.namespace, f.channelId, f.action)
            assertFalse(deniedNotification.canNotify())
            val engine = f.fresh(device = deniedNotification)
            fails { engine.subscribe(f.booking()) }
            assertTrue(engine.state.value.subscriptions.isEmpty())
            assertTrue(f.store.read().isEmpty())
            assertFalse(f.alarms().contains(f.action))
        }
    }

    @Test fun scheduleAndFinalSaveFailuresRollbackWithoutFalseBookedState() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val failsAfterRegistration = object : ReminderDevice(f.context, f.namespace, f.channelId, f.action) {
                override fun schedule(entry: ReminderEntry) {
                    f.entries += entry
                    super.schedule(entry)
                    throw IOException("Injected alarm registration failure")
                }
            }
            val engine = f.fresh(device = failsAfterRegistration)
            fails { engine.subscribe(f.booking()) }
            assertTrue(engine.state.value.subscriptions.isEmpty())
            assertTrue(f.store.read().isEmpty())
            assertNull(f.pending(f.entries.single()))
            assertFalse(f.alarms().contains(f.action))
        }
        fixture { f ->
            f.requirePlatformPermissions()
            val rejectedCommit = object : ReminderStore(f.file) {
                override fun save(records: Map<String, StoredReminder>) {
                    records.values.firstOrNull()?.entry?.let { f.entries += it }
                    if (records.values.any { it.stage == ReminderStage.ACTIVE }) throw IOException("Injected durable subscription failure")
                    super.save(records)
                }
            }
            val engine = f.fresh(store = rejectedCommit)
            fails { engine.subscribe(f.booking()) }
            assertTrue(engine.state.value.subscriptions.isEmpty())
            assertTrue(f.store.read().isEmpty())
            assertFalse(f.alarms().contains(f.action))
            f.entries.forEach { assertNull(f.pending(it)) }
        }
    }

    @Test fun interruptedRegistrationAndCancellationRecoverOnlyTheirOwnResources() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val original = f.subscribe()
            f.store.save(mapOf(original.booking.key to StoredReminder(original, ReminderStage.REGISTERING)))
            val interrupted = f.fresh()
            interrupted.restore()
            assertTrue(interrupted.state.value.subscriptions.isEmpty())
            assertTrue(f.store.read().isEmpty())
            assertNull(f.pending(original))
            val active = f.subscribe(f.booking("other-key"), interrupted)
            f.clock = active.booking.start!!.minusSeconds(60)
            interrupted.handleAlarm(f.device.alarmIntent(active))
            val delivered = interrupted.state.value.subscriptions[active.booking.key]!!
            f.store.save(mapOf(active.booking.key to StoredReminder(delivered, ReminderStage.CANCELING)))
            val canceled = f.fresh()
            canceled.handleAlarm(f.device.alarmIntent(active))
            assertFalse(canceled.state.value.subscriptions.containsKey(active.booking.key))
            canceled.restore()
            assertNull(f.posted(active))
            assertNull(f.pending(active))
            assertTrue(f.store.read().isEmpty())
        }
    }

    @Test fun failedNotifyIsNotDeliveredAndPermissionRecoveryRearmsOriginalTrigger() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val entry = f.subscribe()
            val failNotify = object : ReminderDevice(f.context, f.namespace, f.channelId, f.action) {
                override fun post(entry: ReminderEntry) {
                    super.post(entry)
                    throw IOException("Injected notification delivery failure")
                }
            }
            val engine = f.fresh(device = failNotify)
            f.clock = entry.booking.start!!.minusSeconds(60)
            fails { engine.handleAlarm(failNotify.alarmIntent(entry)) }
            assertFalse(engine.state.value.subscriptions[entry.booking.key]!!.delivered)
            assertFalse(f.store.read()[entry.booking.key]!!.entry.delivered)
            assertNull(f.posted(entry))
            val denied = object : ReminderDevice(f.context, f.namespace, f.channelId, f.action) {
                override fun canNotify() = false
            }
            val noPermission = f.fresh(device = denied)
            fails { noPermission.handleAlarm(denied.alarmIntent(entry)) }
            noPermission.restore()
            assertNotNull(noPermission.state.value.error)
            assertFalse(noPermission.state.value.subscriptions[entry.booking.key]!!.delivered)
            val recovered = f.fresh()
            recovered.restore()
            assertEquals(entry.triggerAt, recovered.state.value.subscriptions[entry.booking.key]!!.triggerAt)
            assertNotNull(f.pending(entry))
            recovered.handleAlarm(f.device.alarmIntent(entry))
            assertTrue(recovered.state.value.subscriptions[entry.booking.key]!!.delivered)
            assertNotNull(f.posted(entry))
            // Cancellation needs neither notification nor exact-alarm permission, even for a posted reminder.
            assertTrue(noPermission.cancel(entry.booking.key))
            assertNull(f.posted(entry))
            assertNull(f.pending(entry))
        }
    }

    @Test fun interruptedPostingReconcilesActualTokenButCannotFalselyClaimOrRepeatDelivery() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val entry = f.subscribe()
            f.clock = entry.booking.start!!.minusSeconds(60)
            f.store.save(mapOf(entry.booking.key to StoredReminder(entry, ReminderStage.POSTING)))
            val uncertain = f.fresh()
            uncertain.restore()
            assertFalse(uncertain.state.value.subscriptions[entry.booking.key]!!.delivered)
            assertNotNull(uncertain.state.value.error)
            uncertain.handleAlarm(f.device.alarmIntent(entry))
            assertNull(f.posted(entry))
            f.device.post(entry)
            val reconciled = f.fresh()
            reconciled.restore()
            assertTrue(reconciled.state.value.subscriptions[entry.booking.key]!!.delivered)
            f.device.cancelNotification(entry)
            reconciled.handleAlarm(f.device.alarmIntent(entry))
            assertNull(f.posted(entry))
        }
    }

    @Test fun failedDeliverySaveKeepsActualNotificationRecoverableWithoutPostingTwice() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val entry = f.subscribe()
            var rejectDelivered = true
            val failingStore = object : ReminderStore(f.file) {
                override fun save(records: Map<String, StoredReminder>) {
                    if (rejectDelivered && records.values.any { it.entry.delivered }) throw IOException("Injected delivery commit failure")
                    super.save(records)
                }
            }
            val engine = f.fresh(store = failingStore)
            f.clock = entry.booking.start!!.minusSeconds(60)
            fails { engine.handleAlarm(f.device.alarmIntent(entry)) }
            assertNotNull(f.posted(entry))
            assertFalse(engine.state.value.subscriptions[entry.booking.key]!!.delivered)
            assertEquals(ReminderStage.POSTING, f.store.read()[entry.booking.key]!!.stage)
            rejectDelivered = false
            engine.restore()
            assertTrue(engine.state.value.subscriptions[entry.booking.key]!!.delivered)
            f.device.cancelNotification(entry)
            engine.handleAlarm(f.device.alarmIntent(entry))
            assertNull(f.posted(entry))
        }
    }

    @Test fun interruptedOsCancellationInvalidatesTokenBeforeRestoreCanFinishCleanup() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val entry = f.subscribe()
            val brokenCancel = object : ReminderDevice(f.context, f.namespace, f.channelId, f.action) {
                override fun cancelAlarm(entry: ReminderEntry) { throw IOException("Injected OS cancel failure") }
            }
            val engine = f.fresh(device = brokenCancel)
            fails { engine.cancel(entry.booking.key) }
            assertFalse(engine.state.value.subscriptions.containsKey(entry.booking.key))
            assertEquals(ReminderStage.CANCELING, f.store.read()[entry.booking.key]!!.stage)
            f.clock = entry.booking.start!!.minusSeconds(60)
            engine.handleAlarm(brokenCancel.alarmIntent(entry))
            assertNull(f.posted(entry))
            val recovered = f.fresh()
            recovered.restore()
            assertTrue(f.store.read().isEmpty())
            assertNull(f.pending(entry))
            assertNull(f.posted(entry))
        }
    }

    @Test fun canceledCallerCannotSplitAnAlreadyStartedDurableCommit() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val scheduled = CountDownLatch(1)
            val finishRegistration = CountDownLatch(1)
            val cancelling = CountDownLatch(1)
            val finishCancellation = CountDownLatch(1)
            val controlled = object : ReminderDevice(f.context, f.namespace, f.channelId, f.action) {
                override fun schedule(entry: ReminderEntry) {
                    f.entries += entry
                    super.schedule(entry)
                    scheduled.countDown()
                    check(finishRegistration.await(10, TimeUnit.SECONDS))
                }
                override fun cancelAlarm(entry: ReminderEntry) {
                    cancelling.countDown()
                    check(finishCancellation.await(10, TimeUnit.SECONDS))
                    super.cancelAlarm(entry)
                }
            }
            val engine = f.fresh(device = controlled)
            coroutineScope {
                val registration = async(start = CoroutineStart.UNDISPATCHED) { engine.subscribe(f.booking()) }
                try {
                    assertTrue(scheduled.await(10, TimeUnit.SECONDS))
                    registration.cancel()
                } finally { finishRegistration.countDown() }
                registration.join()
                val entry = f.entries.single()
                assertEquals(entry, f.store.read()[entry.booking.key]!!.entry)
                assertEquals(entry, engine.state.value.subscriptions[entry.booking.key])
                val cancellation = async(start = CoroutineStart.UNDISPATCHED) { engine.cancel(entry.booking.key) }
                try {
                    assertTrue(cancelling.await(10, TimeUnit.SECONDS))
                    cancellation.cancel()
                } finally { finishCancellation.countDown() }
                cancellation.join()
                assertTrue(f.store.read().isEmpty())
                assertNull(f.pending(entry))
                assertNull(f.posted(entry))
            }
        }
    }

    @Test fun fiveMinuteBoundaryAndExpiredAlarmCannotBackNotify() = runBlocking {
        fixture { f ->
            f.requirePlatformPermissions()
            val boundary = f.booking().copy(startUtc = f.clock.plusSeconds(300).toString())
            fails { f.engine.subscribe(boundary) }
            assertTrue(f.engine.state.value.subscriptions.isEmpty())
            val entry = f.subscribe()
            f.clock = entry.booking.start!!
            f.engine.handleAlarm(f.device.alarmIntent(entry))
            assertNull(f.posted(entry))
            assertNull(f.pending(entry))
            assertTrue(f.engine.state.value.subscriptions.isEmpty())
            assertTrue(f.store.read().isEmpty())
        }
    }

    @Test fun corruptAndOversizedFilesStayUntouchedAndNeverBecomeEmptySuccessfulState() = runBlocking {
        fixture { f ->
            val corrupt = "{\"version\":1,\"entries\":[{\"key\":\"healthy-user-key\"}]}".toByteArray()
            f.file.writeBytes(corrupt)
            val corruptEngine = f.fresh()
            fails { corruptEngine.restore() }
            assertFalse(corruptEngine.state.value.ready)
            assertNotNull(corruptEngine.state.value.error)
            fails { corruptEngine.subscribe(f.booking()) }
            assertArrayEquals(corrupt, f.file.readBytes())
            val oversized = ByteArray(512 * 1024 + 1) { 'x'.code.toByte() }
            f.file.writeBytes(oversized)
            val oversizedEngine = f.fresh()
            fails { oversizedEngine.restore() }
            assertFalse(oversizedEngine.state.value.ready)
            assertArrayEquals(oversized, f.file.readBytes())
            val malformedUtf8 = byteArrayOf('{'.code.toByte(), 0xc3.toByte(), 0x28, '}'.code.toByte())
            f.file.writeBytes(malformedUtf8)
            val malformedEngine = f.fresh()
            fails { malformedEngine.restore() }
            assertFalse(malformedEngine.state.value.ready)
            assertArrayEquals(malformedUtf8, f.file.readBytes())
        }
    }
}
