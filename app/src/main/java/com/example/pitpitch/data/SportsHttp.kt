package com.example.pitpitch.data

import com.example.pitpitch.BuildConfig
import com.example.pitpitch.data.repository.SportsHttpException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared connections; each request has a total deadline and a bounded UTF-8 response. */
internal object SportsHttp {
    const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun request(url: String, timeoutMs: Int = 12_000, totalTimeoutMs: Int = 20_000): String {
        val http = if (timeoutMs == 12_000 && totalTimeoutMs == 20_000) client else client.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(totalTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val call = http.newCall(Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "PitPitch/${BuildConfig.VERSION_NAME}").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (it.code != 200) throw SportsHttpException(it.code)
                            val body = it.body ?: throw IOException("Missing response body")
                            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Response exceeds 16 MiB")
                            val source = body.source()
                            val buffer = Buffer()
                            while (true) {
                                val count = source.read(buffer, minOf(8192L, MAX_RESPONSE_BYTES - buffer.size + 1))
                                if (count == -1L) break
                                if (buffer.size > MAX_RESPONSE_BYTES) throw IOException("Response exceeds 16 MiB")
                            }
                            buffer.readUtf8()
                        }
                        continuation.resume(text)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
}
