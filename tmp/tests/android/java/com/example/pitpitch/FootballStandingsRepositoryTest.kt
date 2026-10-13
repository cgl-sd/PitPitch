package com.example.pitpitch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.FootballStore
import com.example.pitpitch.data.db.LocalDataImporter
import com.example.pitpitch.data.db.PitPitchDatabase
import com.example.pitpitch.data.db.SourceSnapshotRow
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class FootballStandingsRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val url = EspnFootballAdapter.standingsUrl("eng.1", 2026)
    private val oldTime = "2026-01-01T00:00:00Z"
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun database() = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, PitPitchDatabase::class.java).build()
    private fun tables(name: String) = EspnFootballAdapter.tables(fixture(name), 2026)

    private suspend fun seedFive(db: PitPitchDatabase, time: String = oldTime) {
        val body = fixture("espn-standings-five.json")
        FootballStore(db).writeStandings("eng.1", 2026, EspnFootballAdapter.tables(body, 2026), time)
        db.localData().putSnapshots(listOf(SourceSnapshotRow(url, "network", body, time, LocalDataImporter.sha(body.toByteArray()))))
    }

    @Test fun completedDayRefreshPersistsSixGamesAndProviderRankAndPointsOnce() = runBlocking {
        val db = database()
        try {
            seedFive(db)
            val store = FootballStore(db)
            val old = tables("espn-standings-five.json")
            store.writeStandings("eng.1", 2025, old, oldTime)
            store.writeStandings("esp.1", 2026, old, oldTime)
            assertEquals("5", store.competitionTables("eng.1", 2026)!!.first.single().entries.single().played)
            var standingsRequests = 0
            val client = JsonClient(database = db, transport = { requested ->
                when {
                    requested == url -> { standingsRequests++; fixture("espn-standings-six.json") }
                    "/scoreboard?dates=" in requested -> fixture("espn-city-finished-day.json")
                    else -> error("Unexpected request: $requested")
                }
            })
            val repo = EuropeanFootballRepository(client, store)
            assertTrue(repo.refreshDays("eng.1", 2026, listOf("20260919", "20260920")))
            assertEquals(1, standingsRequests)
            val match = store.seasonMatches(2026).single()
            assertEquals("完场", match.status)
            assertEquals("3 : 1", match.score)
            val expected = tables("espn-standings-six.json")
            val persisted = FootballStore(db).competitionTables("eng.1", 2026)!!
            assertEquals(expected, persisted.first)
            val city = persisted.first.single().entries.single()
            assertEquals("6", city.played)
            assertEquals("7", city.position)
            assertEquals("9", city.points)
            assertEquals("2", city.deductions)
            assertEquals("Provider points adjustment", city.providerNote)
            assertNotEquals(oldTime, persisted.second)
            val reread = EuropeanFootballRepository(JsonClient(transport = { error("Local read must not request $it") }), FootballStore(db)).season(2026)
            assertEquals(expected, reread.tables.getValue("eng.1").data)
            assertEquals(persisted.second, reread.tables.getValue("eng.1").fetchedUtc)
            assertEquals(old to oldTime, store.competitionTables("eng.1", 2025))
            assertEquals(old to oldTime, store.competitionTables("esp.1", 2026))
        } finally { db.close() }
    }

    @Test fun offlineWrongSeasonBrokenAndEmptyStandingsKeepOldFactsAndAcquisitionTime() = runBlocking {
        val failures: List<() -> String> = listOf(
            { throw IOException("offline") },
            { JSONObject(fixture("espn-standings-six.json")).put("season", JSONObject().put("year", 2025)).toString() },
            { "{broken" },
            { "{\"season\":{\"year\":2026},\"children\":[]}" }
        )
        for (failure in failures) {
            val db = database()
            try {
                seedFive(db)
                val store = FootballStore(db)
                val repo = EuropeanFootballRepository(JsonClient(database = db, transport = { requested ->
                    if (requested == url) failure() else fixture("espn-city-finished-day.json")
                }), store)
                assertTrue(repo.refreshDays("eng.1", 2026, listOf("20260919")))
                assertEquals("完场", store.seasonMatches(2026).single().status)
                assertEquals(tables("espn-standings-five.json") to oldTime, FootballStore(db).competitionTables("eng.1", 2026))
                val raw = db.localData().snapshot(url, "network")!!
                assertEquals(fixture("espn-standings-five.json"), raw.body)
                assertEquals(oldTime, raw.fetchedUtc)
                val fallback = repo.refreshStandings("eng.1", 2026)
                assertEquals("5", fallback.data.single().entries.single().played)
                assertEquals(oldTime, fallback.fetchedUtc)
                assertTrue(fallback.cached)
                assertNotNull(fallback.error)
                val refreshed = repo.season(2026, "eng.1")
                assertTrue(refreshed.errors.any { it.startsWith("英超积分：") })
                assertNotNull(refreshed.tables.getValue("eng.1").error)
                assertEquals(oldTime, refreshed.tables.getValue("eng.1").fetchedUtc)
            } finally { db.close() }
        }
    }

    @Test fun missingResponseCacheStillFallsBackToPersistedStandings() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val old = tables("espn-standings-five.json")
            store.writeStandings("eng.1", 2026, old, oldTime)
            val repo = EuropeanFootballRepository(JsonClient(transport = { throw IOException("offline") }), store)
            val response = repo.refreshStandings("eng.1", 2026)
            assertEquals(old, response.data)
            assertEquals(oldTime, response.fetchedUtc)
            assertNotNull(response.error)
            assertEquals(old to oldTime, FootballStore(db).competitionTables("eng.1", 2026))
        } finally { db.close() }
    }

    @Test fun olderAcquisitionCannotReplaceNewerPersistedStandings() = runBlocking {
        val db = database()
        try {
            val newerTime = Instant.now().plusSeconds(3600).toString()
            seedFive(db, newerTime)
            val repo = EuropeanFootballRepository(JsonClient(database = db, transport = { fixture("espn-standings-six.json") }), FootballStore(db))
            val response = repo.refreshStandings("eng.1", 2026)
            assertEquals(tables("espn-standings-five.json"), response.data)
            assertEquals(newerTime, response.fetchedUtc)
            assertTrue(response.cached)
            assertEquals(response.data to newerTime, FootballStore(db).competitionTables("eng.1", 2026))
        } finally { db.close() }
    }

    @Test fun standingsCancellationPropagatesWithoutUndoingFinishedDay() = runBlocking {
        val db = database()
        try {
            seedFive(db)
            val store = FootballStore(db)
            val repo = EuropeanFootballRepository(JsonClient(database = db, transport = { requested ->
                if (requested == url) throw CancellationException("cancel standings")
                fixture("espn-city-finished-day.json")
            }), store)
            try {
                repo.refreshDays("eng.1", 2026, listOf("20260919"))
                fail("Cancellation must propagate")
            } catch (expected: CancellationException) {
                assertEquals("cancel standings", expected.message)
            }
            assertEquals("完场", store.seasonMatches(2026).single().status)
            assertEquals(tables("espn-standings-five.json") to oldTime, FootballStore(db).competitionTables("eng.1", 2026))
        } finally { db.close() }
    }
}
