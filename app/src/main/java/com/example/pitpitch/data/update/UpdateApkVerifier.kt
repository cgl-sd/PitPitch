package com.example.pitpitch.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

internal data class InstalledUpdateIdentity(val versionName: String, val versionCode: Long, val signers: Set<String>)

internal class UpdateApkVerifier(context: Context) {
    private val app = context.applicationContext
    private val packages = app.packageManager
    private val flags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun installed(): InstalledUpdateIdentity {
        val info = packages.getPackageInfo(app.packageName, flags)
        return InstalledUpdateIdentity(info.versionName ?: error("无法读取已安装版本"), versionCode(info), signers(info))
    }

    @Suppress("DEPRECATION")
    suspend fun verify(file: File, pending: PendingUpdate) {
        require(file.isFile && file.length() == pending.release.apk.size) { "APK 文件不完整或大小不匹配" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(UpdatePolicy.matchesDigest(pending.digest, digest.digest().hex())) { "APK SHA-256 校验失败" }
        val archive = packages.getPackageArchiveInfo(file.absolutePath, flags) ?: error("APK 无法解析")
        val installed = installed()
        UpdatePolicy.validateArchive(app.packageName, installed.versionCode, installed.signers,
            archive.packageName, archive.versionName, versionCode(archive), signers(archive), pending.release.version)
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return certificates.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }
}

private fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        chars[index * 2] = digits[value ushr 4]
        chars[index * 2 + 1] = digits[value and 15]
    }
    return String(chars)
}
