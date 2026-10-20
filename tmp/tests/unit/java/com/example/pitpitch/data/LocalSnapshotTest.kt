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
    @Test fun providerOffsetTimestampsAreComparedAsInstants() {
        val offsetBundled = RemoteSnapshot("November package", "2026-11-01T00:00:00+00:00", archived = true)
        val older = RemoteSnapshot("October cache", "2026-10-15T00:00:00+00:00", cached = true)
        val newer = RemoteSnapshot("November refresh", "2026-11-05T00:00:00+00:00", cached = true)
        assertSame(offsetBundled, preferredLocalSnapshot(offsetBundled, older))
        assertSame(newer, preferredLocalSnapshot(offsetBundled, newer))
    }
    @Test fun offsetsWhereTextOrderDiffersFromInstantOrderUseTheInstant() {
        val bundled = RemoteSnapshot("November package", "2026-11-01T00:00:00Z", archived = true)
        // Same instant expressed in +08:00; the APK wins the tie.
        assertSame(bundled, preferredLocalSnapshot(bundled, RemoteSnapshot("tie", "2026-11-01T08:00:00+08:00", cached = true)))
        // 2026-10-31T23:30 at -02:00 is 2026-11-01T01:30Z, later than the package despite its earlier text.
        val later = RemoteSnapshot("later", "2026-10-31T23:30:00-02:00", cached = true)
        assertSame(later, preferredLocalSnapshot(bundled, later))
    }
}
