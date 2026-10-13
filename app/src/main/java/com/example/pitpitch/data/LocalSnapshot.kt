package com.example.pitpitch.data

import java.time.Instant

/** Compare resource acquisition times, never installation/read times. Ties favor the APK. */
fun <T> preferredLocalSnapshot(bundled: RemoteSnapshot<T>?, cached: RemoteSnapshot<T>?): RemoteSnapshot<T>? {
    val validCache = cached?.takeIf { runCatching { Instant.parse(it.fetchedUtc) }.isSuccess }
    if (bundled == null) return validCache
    val bundledTime = runCatching { Instant.parse(bundled.fetchedUtc) }.getOrNull() ?: return bundled
    return validCache?.takeIf { Instant.parse(it.fetchedUtc).isAfter(bundledTime) } ?: bundled
}
