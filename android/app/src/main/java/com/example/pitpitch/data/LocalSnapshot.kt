package com.example.pitpitch.data

import java.time.Instant

/** Compare resource acquisition times, never installation/read times. Ties favor the APK. */
fun <T> preferredLocalSnapshot(bundled: RemoteSnapshot<T>?, cached: RemoteSnapshot<T>?): RemoteSnapshot<T>? {
    fun time(snapshot: RemoteSnapshot<T>): Instant? =
        parseProviderInstant(snapshot.fetchedUtc)?.let(Instant::parse)
    val validCache = cached?.takeIf { time(it) != null }
    if (bundled == null) return validCache
    // An unknown package time must not beat a cache with a real acquisition time.
    val bundledTime = bundled.let(::time) ?: return validCache ?: bundled
    return validCache?.takeIf { requireNotNull(time(it)).isAfter(bundledTime) } ?: bundled
}
