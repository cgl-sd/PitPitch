package com.example.pitpitch.data.update

import java.net.URI
import java.security.MessageDigest

internal const val UPDATE_REPOSITORY = "cgl-sd/PitPitch"
internal const val LATEST_RELEASE_URL = "https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"

internal data class UpdateVersion(val minor: Int, val patch: Int) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int = minor.compareTo(other.minor).takeIf { it != 0 } ?: patch.compareTo(other.patch)
    override fun toString(): String = "0.$minor.$patch"

    companion object {
        private val pattern = Regex("0\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
        fun parse(value: String): UpdateVersion? {
            val match = pattern.matchEntire(value) ?: return null
            return UpdateVersion(match.groupValues[1].toIntOrNull() ?: return null, match.groupValues[2].toIntOrNull() ?: return null)
        }
    }
}

internal data class ReleaseAsset(val name: String, val url: String, val size: Long)
internal data class ReleaseMetadata(val tag: String, val draft: Boolean, val prerelease: Boolean, val assets: List<ReleaseAsset>)
internal data class UpdateRelease(val version: UpdateVersion, val tag: String, val apk: ReleaseAsset, val checksum: ReleaseAsset)

internal object UpdatePolicy {
    private val checksumPattern = Regex("([a-fA-F0-9]{64})(?:[ \\t]+\\*?([^\\r\\n]+))?")
    val digestPattern = Regex("[a-f0-9]{64}")

    fun release(metadata: ReleaseMetadata): UpdateRelease {
        require(!metadata.draft && !metadata.prerelease) { "不支持草稿或预发布版本" }
        val version = UpdateVersion.parse(metadata.tag.removePrefix("v")) ?: error("发布版本格式无效，只支持 0.x.x 正式版本")
        val apkName = "PitPitch-$version.apk"
        val apk = metadata.assets.singleOrNull { it.name == apkName } ?: error("发布缺少唯一的 APK 文件")
        val checksum = metadata.assets.singleOrNull { it.name == "$apkName.sha256" } ?: error("发布缺少唯一的 SHA-256 文件")
        require(apk.size > 0 && checksum.size in 1..4096) { "发布文件大小无效" }
        require(validAssetUrl(apk.url, metadata.tag, apk.name) && validAssetUrl(checksum.url, metadata.tag, checksum.name)) { "发布下载地址不属于指定仓库" }
        return UpdateRelease(version, metadata.tag, apk, checksum)
    }

    fun validAssetUrl(value: String, tag: String, name: String): Boolean = try {
        val url = URI(value)
        url.scheme == "https" && url.host == "github.com" && url.port == -1 && url.rawUserInfo == null &&
            url.rawQuery == null && url.rawFragment == null && url.rawPath == "/$UPDATE_REPOSITORY/releases/download/$tag/$name"
    } catch (_: Exception) { false }

    fun checksum(text: String, apkName: String): String {
        val line = text.trim()
        val match = checksumPattern.matchEntire(line) ?: error("SHA-256 文件格式无效")
        require(match.groupValues[2].isEmpty() || match.groupValues[2] == apkName) { "SHA-256 文件名与 APK 不匹配" }
        return match.groupValues[1].lowercase()
    }

    fun validateArchive(
        installedPackage: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        archivePackage: String,
        archiveVersion: String?,
        archiveVersionCode: Long,
        archiveSigners: Set<String>,
        expectedVersion: UpdateVersion,
    ) {
        require(archivePackage == installedPackage) { "APK 应用标识不匹配" }
        require(archiveVersion == expectedVersion.toString()) { "APK 版本与发布版本不匹配" }
        require(archiveVersionCode > installedVersionCode) { "APK 版本号不高于已安装版本" }
        require(installedSigners.isNotEmpty() && archiveSigners == installedSigners) { "APK 签名与已安装应用不匹配" }
    }

    fun matchesDigest(expected: String, actual: String): Boolean =
        expected.matches(digestPattern) && actual.matches(digestPattern) &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))
}
