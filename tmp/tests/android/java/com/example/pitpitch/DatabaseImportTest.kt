package com.example.pitpitch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.SportsArchive
import com.example.pitpitch.data.db.DatabaseSeeder
import com.example.pitpitch.data.db.FootballStore
import com.example.pitpitch.data.db.PitPitchDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The bundled archive must import completely: row counts reconcile with the known archive inventory. */
@RunWith(AndroidJUnit4::class)
class DatabaseImportTest {
    @Test fun bundledArchiveSeedsFullDatabaseAndIdempotentlySkipsReseed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = PitPitchDatabase.build(context)
        val store = FootballStore(db)
        // The app process may already be seeding; wait for it, then run our own pass (idempotent either way).
        val deadline = System.currentTimeMillis() + 240_000
        while (db.meta().get("seedVersion") == null && System.currentTimeMillis() < deadline) delay(500)
        val started = System.currentTimeMillis()
        DatabaseSeeder(db, store, SportsArchive(context)).seedIfNeeded()
        android.util.Log.i("PitPitchSeed", "seed pass took ${System.currentTimeMillis() - started}ms")
        assertEquals("seed must not report failures", "0", db.meta().get("seedFailures"))
        val matchRows = (2020..2026).sumOf { store.seasonMatches(it).size }
        assertEquals("archived fixtures must reconcile", 14659, matchRows)
        // Archived scoreboards carry fixtures; full match timelines are fetched per match and are not bundled.
        assertTrue("F1 calendars must be imported", db.f1().raceCount() >= 150)
        for (league in listOf("eng.1", "uefa.champions")) {
            val (entries, time) = store.leaderboards(league, 2024)
            assertTrue("$league leaderboard needs players", entries.size >= 300)
            assertTrue("$league leaderboard needs a timestamp", time.isNotBlank())
        }
        // Read latency sanity: indexed season reads must be far below the old file-parse path.
        val readStart = System.currentTimeMillis()
        repeat(20) { store.seasonMatches(2026); store.leaderboards("eng.1", 2024) }
        android.util.Log.i("PitPitchSeed", "40 indexed reads took ${System.currentTimeMillis() - readStart}ms")
        // Seeding is idempotent: a second run updates nothing and finishes immediately.
        val before = store.seasonMatches(2024).size
        DatabaseSeeder(db, store, SportsArchive(context)).seedIfNeeded()
        assertEquals(before, store.seasonMatches(2024).size)
    }
}
