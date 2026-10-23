package com.example.pitpitch

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.decode.SvgDecoder
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class PitPitchApplication : Application(), ImageLoaderFactory {
    val localBackend by lazy { com.example.pitpitch.data.repository.LocalBackendRepository(this, com.example.pitpitch.data.db.PitPitchDatabase.build(this)) }
    private val backendScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        backendScope.launch {
            runCatching { localBackend.prepare() }.onFailure { android.util.Log.e("PitPitchBackend", "Local import failed", it) }
        }
        backendScope.launch {
            runCatching { com.example.pitpitch.data.reminder.MatchReminders.get(this@PitPitchApplication).restore() }
                .onFailure { android.util.Log.e("PitPitchReminder", "Reminder restore failed", it) }
        }
    }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("team_badges")).maxSizeBytes(50L * 1024 * 1024).build() }
        .okHttpClient {
            OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "PitPitch/${BuildConfig.VERSION_NAME} (Android sports schedule)").build()) }
                .build()
        }
        .build()
}
