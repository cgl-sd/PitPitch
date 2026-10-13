package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class F1StandingsDatabaseTest {
    @Test fun alreadySeededLegacyDatabaseRestoresMediaIdsWithoutReplacingNewerFacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val archive = SportsArchive(context)
            val store = FootballStore(db)
            val newer = "2026-10-11T00:00:00Z"
            for (kind in listOf("driver", "constructor")) {
                val endpoint = if (kind == "driver") "driverstandings" else "constructorstandings"
                val rankings = archive.read("https://api.jolpi.ca/ergast/f1/2020/$endpoint.json?limit=100") {
                    JolpicaAdapter.standings(it, kind == "constructor", 2020)
                }!!.data
                val legacy = rankings.copy(entries = rankings.entries.map { it.copy(points = "999", driverId = null, constructorId = null, nationality = null) })
                store.writeStandings(2020, kind, legacy, newer)
            }
            val results = archive.read("https://api.jolpi.ca/ergast/f1/2020/1/results.json?limit=100") { JolpicaAdapter.results(it) }!!.data
            store.writeResults(2020, 1, results.map { it.copy(driverId = null, constructorId = null, nationality = null, points = "999") }, newer)
            db.meta().put(MetaRow("seedVersion", DatabaseSeeder.SEED_VERSION))
            db.meta().put(MetaRow("seedDataRevision", context.assets.open("local-backend-revision.txt").bufferedReader().use { it.readText().trim() }))
            DatabaseSeeder(db, store, archive).seedIfNeeded()
            for (kind in listOf("driver", "constructor")) {
                val response = store.f1Standings(2020, kind, null)!!
                assertEquals(newer, response.second)
                assertTrue(response.first.entries.all { it.points == "999" })
                assertTrue(response.first.entries.all { if (kind == "driver") it.driverId != null else it.constructorId != null })
            }
            assertTrue(store.f1Results(2020, 1).first.all { it.driverId != null && it.constructorId != null && it.points == "999" })
            assertEquals(newer, store.f1Results(2020, 1).second)
            assertEquals("1", db.meta().get("f1MediaBindingVersion"))
            DatabaseSeeder(db, store, archive).seedIfNeeded()
            assertTrue(store.f1Standings(2020, "driver", null)!!.first.entries.all { it.points == "999" })
        } finally { db.close() }
    }
    @Test fun numericOrderAndSnapshotReplacementKeepBothTablesIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val store = FootballStore(db)
            val old = "2026-10-10T00:00:00Z"
            val newer = "2026-10-11T00:00:00Z"
            for (kind in listOf("driver", "constructor")) {
                val entries = (1..12).map { n -> RankingEntry("$n", "$kind-$n", "Team", "${100-n}", "wins",
                    driverId = if (kind == "driver") "driver-$n" else null,
                    constructorId = if (kind == "constructor") "constructor-$n" else null) }
                store.writeStandings(2025, kind, F1Rankings("17", entries), old)
                store.writeStandings(2026, kind, F1Rankings("16", entries), old)
                store.writeStandings(2026, kind, F1Rankings("17", entries), old)
                assertEquals((1..12).map { "$it" }, store.f1Standings(2026, kind, null)!!.first.entries.map { it.position })
                // A revised full response moves the last entity to first and removes the old first.
                val revised = listOf(entries.last().copy(position = "1")) + entries.drop(1).dropLast(1)
                store.writeStandings(2026, kind, F1Rankings("17", revised), newer)
                assertEquals(revised, store.f1Standings(2026, kind, null)!!.first.entries)
                assertEquals(11, db.f1().standings(2026, kind, "17").size)
                // Simulate a residual row written by the previous position-keyed upsert.
                db.f1().upsertStandings(listOf(F1StandingRow(2026, kind, "17", "12", entries.last().name,
                    "Team", entries.last().points, "wins", old, entries.last().driverId, entries.last().constructorId)))
                assertEquals(revised, store.f1Standings(2026, kind, null)!!.first.entries)
                store.writeStandings(2026, kind, F1Rankings("17", entries), old)
                assertEquals(revised, store.f1Standings(2026, kind, null)!!.first.entries)
                store.writeStandings(2026, kind, F1Rankings("17", revised), newer)
                assertEquals(11, db.f1().standings(2026, kind, "17").size)
                assertEquals(entries, store.f1Standings(2026, kind, "16")!!.first.entries)
                assertEquals(entries, store.f1Standings(2025, kind, "17")!!.first.entries)
            }
        } finally { db.close() }
    }
}
