package com.example.pitpitch.data.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface ReleaseSource {
    suspend fun latest(): UpdateRelease?
    suspend fun checksum(release: UpdateRelease): String
}

internal class GithubReleaseClient : ReleaseSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followSslRedirects(false)
        .build()

    override suspend fun latest(): UpdateRelease? {
        val body = request(LATEST_RELEASE_URL, 1024 * 1024L, noReleaseOn404 = true) ?: return null
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")
        val metadata = ReleaseMetadata(
            tag = json.requiredString("tag_name"),
            draft = json.get("draft") as? Boolean ?: error("发布 draft 字段格式无效"),
            prerelease = json.get("prerelease") as? Boolean ?: error("发布 prerelease 字段格式无效"),
            assets = List(assets.length()) { index ->
                val asset = assets.getJSONObject(index)
                val size = asset.get("size")
                require(size is Long || size is Int) { "发布文件大小字段格式无效" }
                ReleaseAsset(asset.requiredString("name"), asset.requiredString("browser_download_url"), (size as Number).toLong())
            },
        )
        return UpdatePolicy.release(metadata)
    }

    override suspend fun checksum(release: UpdateRelease): String {
        require(UpdatePolicy.validAssetUrl(release.checksum.url, release.tag, release.checksum.name)) { "校验文件地址无效" }
        val text = request(release.checksum.url, 4096, noReleaseOn404 = false) ?: error("校验文件不存在")
        return UpdatePolicy.checksum(text, release.apk.name)
    }
    private fun JSONObject.requiredString(key: String): String = get(key) as? String ?: error("发布 $key 字段格式无效")


    private suspend fun request(url: String, limit: Long, noReleaseOn404: Boolean): String? {
        val call = client.newCall(Request.Builder().url(url)
            .header("Accept", if (noReleaseOn404) "application/vnd.github+json" else "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "PitPitch-Android-Updater")
            .build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code == 404 && noReleaseOn404) return@use null
                            if (!it.isSuccessful) throw IOException("GitHub 请求失败（HTTP ${it.code}）")
                            require(it.request.url.isHttps) { "下载重定向不是 HTTPS" }
                            val body = it.body ?: throw IOException("GitHub 返回内容为空")
                            if (body.contentLength() > limit) throw IOException("GitHub 返回内容过大")
                            val source = body.source()
                            val buffer = Buffer()
                            while (true) {
                                val read = source.read(buffer, minOf(8192L, limit - buffer.size + 1))
                                if (read == -1L) break
                                if (buffer.size > limit) throw IOException("GitHub 返回内容过大")
                            }
                            buffer.readUtf8()
                        }
                        if (!continuation.isCancelled) continuation.resume(text)
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}
