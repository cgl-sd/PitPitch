package com.example.pitpitch.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

private val updateFilePattern = Regex("PitPitch-update-[a-f0-9-]{36}\\.apk")

internal enum class PendingStage { TRANSFER, READY, PERMISSION, INSTALLER }
internal data class PendingUpdate(
    val release: UpdateRelease,
    val digest: String,
    val fileName: String,
    val downloadId: Long,
    val stage: PendingStage = PendingStage.TRANSFER,
)

internal sealed interface TransferSnapshot {
    data class Progress(val bytes: Long, val total: Long?, val paused: Boolean) : TransferSnapshot
    data object Complete : TransferSnapshot
    data class Failed(val reason: Int) : TransferSnapshot
    data object Missing : TransferSnapshot
}

internal class UpdateTransfer(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun directory(): File {
        val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("应用下载目录不可用")
        return File(base, "updates").also { require(it.isDirectory || it.mkdirs()) { "无法创建应用下载目录" } }
    }

    fun file(name: String): File {
        require(updateFilePattern.matches(name)) { "更新文件名无效" }
        return File(directory(), name)
    }

    @Suppress("DEPRECATION")
    fun enqueue(pending: PendingUpdate): Long {
        require(UpdatePolicy.validAssetUrl(pending.release.apk.url, pending.release.tag, pending.release.apk.name)) { "APK 下载地址无效" }
        return manager.enqueue(DownloadManager.Request(Uri.parse(pending.release.apk.url))
            .setTitle("PitPitch ${pending.release.version}")
            .setDescription(pending.fileName)
            .setMimeType("application/vnd.android.package-archive")
            // A completed-download tap must not offer installation before our verifier runs.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setVisibleInDownloadsUi(false)
            .setDestinationUri(Uri.fromFile(file(pending.fileName))))
    }

    // A prepared record is persisted before enqueue. Recover the ID if the process died between enqueue and its second write.
    fun recoverId(pending: PendingUpdate): Long? {
        manager.query(DownloadManager.Query())?.use { cursor ->
            while (cursor.moveToNext()) {
                if (matches(cursor, pending)) return cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            }
        }
        return null
    }

    fun inspect(pending: PendingUpdate): TransferSnapshot {
        manager.query(DownloadManager.Query().setFilterById(pending.downloadId))?.use { cursor ->
            if (!cursor.moveToFirst() || !matches(cursor, pending)) return TransferSnapshot.Missing
            return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> TransferSnapshot.Complete
                DownloadManager.STATUS_FAILED -> TransferSnapshot.Failed(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 }
                    TransferSnapshot.Progress(bytes, total, cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PAUSED)
                }
                else -> TransferSnapshot.Missing
            }
        }
        return TransferSnapshot.Missing
    }

    private fun matches(cursor: android.database.Cursor, pending: PendingUpdate): Boolean {
        // DownloadManager has no local URI before opening the destination; redirects can also change COLUMN_URI.
        // The random private filename is a stable marker, queried only within this app's downloads.
        if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)) != pending.fileName) return false
        val destination = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: return true
        val uri = Uri.parse(destination)
        return uri.scheme == "file" && uri.path?.let { File(it).canonicalFile == file(pending.fileName).canonicalFile } == true
    }

    fun remove(pending: PendingUpdate) {
        // Never remove an unrelated/foreign ID, even if a persisted record was altered.
        val id = if (pending.downloadId > 0) pending.downloadId else recoverId(pending)
        if (id != null && id > 0) {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst() && matches(cursor, pending)) manager.remove(id)
            }
        }
        file(pending.fileName).let { if (it.exists() && !it.delete()) error("无法删除更新下载文件") }
    }

    fun removeOrphans(keep: String?) {
        directory().listFiles()?.forEach { file ->
            if (file.name != keep && updateFilePattern.matches(file.name)) {
                require(!file.exists() || file.delete()) { "无法删除过期更新文件" }
            }
        }
    }
}

internal class PendingUpdateStore(context: Context) {
    // Deliberately outside SharedPreferences/auto-backup: a restored DownloadManager ID could belong to another transfer.
    private val state = AtomicFile(File(context.noBackupFilesDir, "github-update-state.json"))

    fun read(): PendingUpdate? {
        val bytes = try { state.readFully() } catch (_: FileNotFoundException) { return null }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val tag = json.getString("tag")
        val release = UpdatePolicy.release(ReleaseMetadata(tag, false, false, listOf(
            ReleaseAsset(json.getString("apkName"), json.getString("apkUrl"), json.getLong("apkSize")),
            ReleaseAsset(json.getString("checksumName"), json.getString("checksumUrl"), json.getLong("checksumSize")),
        )))
        val digest = json.getString("digest")
        require(digest.matches(UpdatePolicy.digestPattern)) { "保存的 SHA-256 无效" }
        val name = json.getString("fileName")
        require(updateFilePattern.matches(name)) { "保存的更新文件名无效" }
        val id = json.getLong("downloadId")
        require(id >= 0) { "保存的下载编号无效" }
        return PendingUpdate(release, digest, name, id, PendingStage.valueOf(json.getString("stage")))
    }

    fun save(pending: PendingUpdate) {
        val release = pending.release
        val json = JSONObject().put("tag", release.tag)
            .put("apkName", release.apk.name).put("apkUrl", release.apk.url).put("apkSize", release.apk.size)
            .put("checksumName", release.checksum.name).put("checksumUrl", release.checksum.url).put("checksumSize", release.checksum.size)
            .put("digest", pending.digest).put("fileName", pending.fileName).put("downloadId", pending.downloadId).put("stage", pending.stage.name)
        val output = state.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            state.finishWrite(output)
        } catch (error: Exception) {
            state.failWrite(output)
            throw error
        }
    }

    fun clear() { state.delete() }
}
