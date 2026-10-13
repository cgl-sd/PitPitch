package com.example.pitpitch.data.reminder

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal enum class ReminderStage { REGISTERING, ACTIVE, CANCELING, POSTING }
internal data class StoredReminder(val entry: ReminderEntry, val stage: ReminderStage)

/** Device-local facts: APK upgrades retain this file, Android backup never copies alarm identities. */
internal open class ReminderStore(private val file: () -> File) {
    constructor(file: File) : this({ file })

    private val atomic by lazy { AtomicFile(file()) }

    open fun read(): Map<String, StoredReminder> {
        val bytes = try {
            atomic.openRead().use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_BYTES) throw IOException("预约提醒文件过大，原文件已保留")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        } catch (error: FileNotFoundException) {
            // AtomicFile also reports inaccessible paths as missing; only an actually absent file is empty.
            val base = atomic.baseFile
            val parent = base.parentFile ?: throw error
            if (base.exists() || File(base.path + ".bak").exists() ||
                !parent.isDirectory || !parent.canRead() || !parent.canExecute()) throw error
            return emptyMap()
        }
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = JSONObject(text)
            require(root.getInt("version") == 1)
            val entries = root.getJSONArray("entries")
            require(entries.length() <= MAX_ENTRIES)
            val result = linkedMapOf<String, StoredReminder>()
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val booking = ReminderBooking(
                    item.getString("key"), item.getString("title"),
                    item.getString("startUtc"), item.getString("location"), item.getString("source"),
                )
                validateBooking(booking)
                val token = item.getString("token")
                require(UUID.fromString(token).toString() == token)
                val triggerAt = item.getLong("triggerAt")
                require(triggerAt == requireNotNull(booking.start).minusSeconds(REMINDER_LEAD_SECONDS).toEpochMilli())
                val entry = ReminderEntry(booking, triggerAt, token, item.getBoolean("delivered"))
                val stage = ReminderStage.valueOf(item.getString("stage"))
                require(!entry.delivered || stage == ReminderStage.ACTIVE || stage == ReminderStage.CANCELING)
                require(result.put(booking.key, StoredReminder(entry, stage)) == null)
            }
            return result
        } catch (error: Exception) {
            throw IOException("预约提醒文件损坏，原文件已保留；无法安全修改预约", error)
        }
    }

    open fun save(records: Map<String, StoredReminder>) {
        require(records.size <= MAX_ENTRIES) { "预约提醒数量已达上限" }
        val entries = JSONArray()
        records.forEach { (key, record) ->
            val entry = record.entry
            val booking = entry.booking
            validateBooking(booking)
            require(key == booking.key)
            entries.put(JSONObject().put("key", booking.key).put("title", booking.title)
                .put("startUtc", booking.startUtc).put("location", booking.location).put("source", booking.source)
                .put("triggerAt", entry.triggerAt).put("token", entry.token).put("delivered", entry.delivered)
                .put("stage", record.stage.name))
        }
        val bytes = JSONObject().put("version", 1).put("entries", entries).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "预约提醒文件已达容量上限" }
        val base = atomic.baseFile
        val parent = requireNotNull(base.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建预约提醒目录")
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw IOException("无法保存预约提醒", error)
        }
        // AtomicFile.finishWrite logs, rather than throws, a failed rename. Do not acknowledge that as a commit.
        if (read() != records) throw IOException("无法完成预约提醒的持久化提交")
    }

    companion object {
        private const val MAX_BYTES = 512 * 1024
        private const val MAX_ENTRIES = 1024

        internal fun validateBooking(booking: ReminderBooking) {
            require(booking.key.isNotEmpty() && booking.key.length <= 512) { "赛事标识无效" }
            require(booking.title.isNotBlank() && booking.title.length <= 2048) { "赛事名称无效" }
            require(booking.location.length <= 2048 && booking.source.length <= 256) { "赛事资料过长" }
            require(booking.startUtc != null && booking.startUtc.length <= 128 && booking.start != null) { "赛程尚未提供有效的开赛时间" }
        }
    }
}
