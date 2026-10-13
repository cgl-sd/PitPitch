package com.example.pitpitch.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal enum class UpdatePhase {
    RECOVERING, IDLE, CHECKING, NO_RELEASE, UP_TO_DATE, AVAILABLE, PREPARING, DOWNLOADING, VERIFYING, READY, PERMISSION, INSTALLER, ERROR,
}

internal data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.RECOVERING,
    val installedVersion: String = "—",
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val paused: Boolean = false,
    val message: String? = null,
) {
    val canCheck: Boolean get() = when (phase) {
        UpdatePhase.IDLE, UpdatePhase.NO_RELEASE, UpdatePhase.UP_TO_DATE, UpdatePhase.ERROR -> true
        else -> false
    }
    val canInstall: Boolean get() = phase == UpdatePhase.READY
    val canCancelDownload: Boolean get() = phase == UpdatePhase.PREPARING || phase == UpdatePhase.DOWNLOADING
}

/** Application-owned work; only the settings UI's resumed lifecycle polls DownloadManager. */
internal class UpdateController private constructor(context: Context, private val releases: ReleaseSource = GithubReleaseClient()) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transfer = UpdateTransfer(app)
    private val store = PendingUpdateStore(app)
    private val verifier = UpdateApkVerifier(app)
    private val lock = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var pending: PendingUpdate? = null
    private var operation: Job? = null
    private var canceling = false

    init {
        scope.launch {
            lock.withLock {
                try {
                    val installed = withContext(Dispatchers.IO) { verifier.installed() }
                    mutableState.value = UpdateState(installedVersion = installed.versionName)
                    pending = withContext(Dispatchers.IO) { store.read() }
                    val saved = pending
                    if (saved != null && alreadyInstalled(saved, installed)) {
                        discard()
                        mutableState.value = UpdateState(UpdatePhase.UP_TO_DATE, installed.versionName)
                    } else {
                        withContext(Dispatchers.IO) { transfer.removeOrphans(saved?.fileName) }
                        if (saved == null) publish(UpdatePhase.IDLE)
                        else if (saved.stage == PendingStage.TRANSFER) {
                            if (saved.downloadId == 0L) {
                                val id = withContext(Dispatchers.IO) { transfer.recoverId(saved) } ?: error("上次下载未开始，请重新检查更新")
                                persist(saved.copy(downloadId = id))
                            }
                            publish(UpdatePhase.DOWNLOADING, saved.release)
                        } else {
                            publish(UpdatePhase.VERIFYING, saved.release)
                            withContext(Dispatchers.IO) { verifier.verify(transfer.file(saved.fileName), saved) }
                            // Preserve the permission/installer marker until the first UI resume handles the return trip.
                            publish(UpdatePhase.READY, saved.release)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(error) }
                finally { initialized.complete(Unit) }
            }
        }
    }

    fun check() {
        if (!state.value.canCheck) return
        operate {
            if (pending != null) discard()
            publish(UpdatePhase.CHECKING)
            val installed = withContext(Dispatchers.IO) { verifier.installed() }
            mutableState.value = state.value.copy(installedVersion = installed.versionName)
            val current = UpdateVersion.parse(installed.versionName) ?: error("已安装版本不是受支持的 0.x.x 版本")
            val release = releases.latest()
            when {
                release == null -> publish(UpdatePhase.NO_RELEASE)
                release.version <= current -> publish(UpdatePhase.UP_TO_DATE)
                else -> publish(UpdatePhase.AVAILABLE, release)
            }
        }
    }

    fun dismissOffer() {
        if (state.value.phase == UpdatePhase.AVAILABLE) publish(UpdatePhase.IDLE)
    }

    fun download() {
        val release = state.value.takeIf { it.phase == UpdatePhase.AVAILABLE }?.release ?: return
        operate {
            publish(UpdatePhase.PREPARING, release)
            val digest = releases.checksum(release)
            val prepared = PendingUpdate(release, digest, "PitPitch-update-${UUID.randomUUID()}.apk", 0)
            // Once enqueue starts, finish persisting its ID even if the user cancels. Cancellation then removes this exact transfer.
            withContext(NonCancellable) {
                persist(prepared)
                val id = withContext(Dispatchers.IO) { transfer.enqueue(prepared) }
                persist(prepared.copy(downloadId = id))
            }
            publish(UpdatePhase.DOWNLOADING, release)
        }
    }

    fun cancelDownload() {
        if (!state.value.canCancelDownload || canceling) return
        canceling = true
        val running = operation
        scope.launch {
            try {
                running?.cancelAndJoin()
                lock.withLock {
                    try { discard(); publish(UpdatePhase.IDLE) }
                    catch (error: Exception) { publish(UpdatePhase.ERROR, message = describe(error)) }
                }
            } finally { canceling = false }
        }
    }

    fun install() {
        if (!state.value.canInstall) return
        operate { launchInstall() }
    }

    /** Called once for each RESUMED interval, including activity/process recreation. */
    private suspend fun resume() {
        initialized.await()
        lock.withLock {
            val saved = pending ?: return@withLock
            try {
                val installed = withContext(Dispatchers.IO) { verifier.installed() }
                mutableState.value = state.value.copy(installedVersion = installed.versionName)
                if (alreadyInstalled(saved, installed)) {
                    discard()
                    publish(UpdatePhase.UP_TO_DATE)
                } else when (saved.stage) {
                    PendingStage.PERMISSION -> {
                        if (app.packageManager.canRequestPackageInstalls()) continueInstall()
                        else {
                            persist(saved.copy(stage = PendingStage.READY))
                            publish(UpdatePhase.READY, saved.release, "未允许安装此来源；安装包已保留，可重试")
                        }
                    }
                    PendingStage.INSTALLER -> {
                        persist(saved.copy(stage = PendingStage.READY))
                        publish(UpdatePhase.READY, saved.release, "安装尚未完成；安装包已保留，可再次打开安装器")
                    }
                    PendingStage.READY -> if (state.value.phase != UpdatePhase.READY) publish(UpdatePhase.READY, saved.release)
                    PendingStage.TRANSFER -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
        }
    }

    suspend fun observeWhileResumed() {
        resume()
        while (coroutineContext.isActive) {
            initialized.await()
            lock.withLock {
                val saved = pending
                if (saved != null && saved.stage == PendingStage.TRANSFER && !canceling) {
                    try {
                        when (val snapshot = withContext(Dispatchers.IO) { transfer.inspect(saved) }) {
                            is TransferSnapshot.Progress -> mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, state.value.installedVersion,
                                saved.release, snapshot.bytes, snapshot.total, snapshot.paused)
                            TransferSnapshot.Complete -> {
                                publish(UpdatePhase.VERIFYING, saved.release)
                                withContext(Dispatchers.IO) { verifier.verify(transfer.file(saved.fileName), saved) }
                                persist(saved.copy(stage = PendingStage.READY))
                                publish(UpdatePhase.READY, saved.release)
                            }
                            is TransferSnapshot.Failed -> error("系统下载失败（原因 ${snapshot.reason}），请重新检查更新")
                            TransferSnapshot.Missing -> error("系统下载记录已丢失，请重新检查更新")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { fail(error) }
                }
            }
            delay(1000)
        }
    }

    private suspend fun launchInstall() {
        val saved = pending ?: error("没有可安装的更新文件")
        publish(UpdatePhase.VERIFYING, saved.release)
        // Reverify at the point of use, not just when the transfer first completes.
        withContext(Dispatchers.IO) { verifier.verify(transfer.file(saved.fileName), saved) }
        try {
            if (!app.packageManager.canRequestPackageInstalls()) {
                persist(saved.copy(stage = PendingStage.PERMISSION))
                publish(UpdatePhase.PERMISSION, saved.release)
                app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", transfer.file(saved.fileName))
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                persist(saved.copy(stage = PendingStage.INSTALLER))
                publish(UpdatePhase.INSTALLER, saved.release)
                // Only Android's installer decides whether to install; no silent install or data-clearing path.
                app.startActivity(intent)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // A missing permission screen/installer must not destroy a verified APK or claim installation succeeded.
            persist(saved.copy(stage = PendingStage.READY))
            publish(UpdatePhase.READY, saved.release, "无法打开系统安装流程：${describe(error)}。安装包已保留，可重试")
        }
    }

    /** Same single-operation guard as a user tap, so the permission return trip cannot stack an installer. */
    private fun continueInstall() = operate { launchInstall() }

    private fun operate(block: suspend () -> Unit) {
        if (operation?.isActive == true || canceling) return
        operation = scope.launch {
            initialized.await()
            lock.withLock {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(error) }
            }
        }
    }

    private suspend fun persist(value: PendingUpdate) {
        pending = value
        withContext(Dispatchers.IO) { store.save(value) }
    }

    private suspend fun discard() {
        withContext(NonCancellable + Dispatchers.IO) {
            pending?.let { transfer.remove(it) }
            store.clear()
        }
        pending = null
    }

    private suspend fun fail(error: Exception) {
        var message = describe(error)
        try {
            discard()
            withContext(NonCancellable + Dispatchers.IO) { transfer.removeOrphans(null) }
        } catch (cleanup: Exception) { message += "；清理失败：${describe(cleanup)}" }
        publish(UpdatePhase.ERROR, message = message)
    }

    private fun publish(phase: UpdatePhase, release: UpdateRelease? = null, message: String? = null) {
        mutableState.value = UpdateState(phase, state.value.installedVersion, release, message = message)
    }

    private fun alreadyInstalled(saved: PendingUpdate, installed: InstalledUpdateIdentity): Boolean =
        UpdateVersion.parse(installed.versionName)?.let { it >= saved.release.version } == true

    private fun describe(error: Exception): String = error.message?.takeIf { it.isNotBlank() } ?: "更新操作失败，请检查网络或系统设置后重试"

    companion object {
        @Volatile private var instance: UpdateController? = null
        fun get(context: Context): UpdateController = instance ?: synchronized(this) {
            instance ?: UpdateController(context.applicationContext).also { instance = it }
        }
    }
}
