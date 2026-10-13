package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class LocalSnapshotTest {
    private val bundled = RemoteSnapshot("November package", "2026-11-01T00:00:00Z", archived = true)
    @Test fun upgradeReplacesOlderCacheAndTieUsesPackage() {
        for (time in listOf("2026-10-15T00:00:00Z", bundled.fetchedUtc)) {
            assertSame(bundled, preferredLocalSnapshot(bundled, RemoteSnapshot("old cache", time, cached = true)))
        }
    }
    @Test fun newerCacheSurvivesUpgradeWithOriginalTimestamp() {
        val cached = RemoteSnapshot("November refresh", "2026-11-05T00:00:00Z", cached = true)
        assertSame(cached, preferredLocalSnapshot(bundled, cached))
        assertSame(cached, preferredLocalSnapshot(null, cached))
    }
    @Test fun invalidCacheTimeNeverShadowsPackage() {
        assertSame(bundled, preferredLocalSnapshot(bundled, RemoteSnapshot("bad", "invalid", cached = true)))
        assertNull(preferredLocalSnapshot<String>(null, RemoteSnapshot("bad", "", cached = true)))
        assertNull(preferredLocalSnapshot<String>(null, null))
    }
}
