package com.example.pitpitch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackendSnapshotIntegrityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val oldTime = "2026-01-01T00:00:00Z"
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun database() = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, PitPitchDatabase::class.java).build()
    private suspend fun cache(db: PitPitchDatabase, url: String, body: String, time: String = oldTime) {
        db.localData().putSnapshots(listOf(SourceSnapshotRow(url, "network", body, time, LocalDataImporter.sha(body.toByteArray()))))
    }

    @Test fun invalidDay200KeepsResponseMatchTimelineAndSourceTimeAndIsNotSuccessful() = runBlocking {
        val league = footballCompetitions.first { it.id == "eng.1" }
        val day = "20260919"
        val url = EspnFootballAdapter.dayUrl(league, day)
        val body = fixture("espn-day-overlay.json")
        val invalidField = JSONObject(body).apply {
            getJSONArray("events").getJSONObject(0).getJSONArray("competitions").getJSONObject(0).remove("status")
        }.toString()
        val invalidTimeline = JSONObject(body).apply {
            getJSONArray("events").getJSONObject(0).getJSONArray("competitions").getJSONObject(0).put("details", JSONArray().put("broken"))
        }.toString()
        for (bad in listOf("<html>temporarily unavailable</html>", invalidField, invalidTimeline)) {
            val db = database()
            try {
                val store = FootballStore(db)
                val parsed = EspnFootballAdapter.scoreboard(body, league, 2026)
                assertTrue(parsed.timeline.values.all { it.isNotEmpty() })
                assertTrue(parsed.timeline.isNotEmpty())
                store.writeMatches(parsed.matches, league.id, league.title, 2026, oldTime, url, "day")
                parsed.timeline.forEach { (id, events) -> store.writeTimeline(id, events, oldTime, url, "day") }
                cache(db, url, body)
                val before = store.seasonMatches(2026)
                val repo = EuropeanFootballRepository(JsonClient(database = db, transport = { bad }), store)
                assertFalse(repo.refreshDays(league.id, 2026, listOf(day)))
                assertEquals(before, store.seasonMatches(2026))
                assertEquals(oldTime, store.sourceTime(url))
                assertEquals(body, db.localData().snapshot(url, "network")!!.body)
                assertEquals(oldTime, db.localData().snapshot(url, "network")!!.fetchedUtc)
                val schedule = EspnFootballAdapter.scheduleUrl(league.id, 2026)
                cache(db, schedule, body)
                val refreshed = repo.season(2026, league.id)
                assertTrue(refreshed.errors.any { it.startsWith("${league.title}：") })
                assertEquals(before, store.seasonMatches(2026))
                assertEquals(body, db.localData().snapshot(schedule, "network")!!.body)
                assertEquals(oldTime, db.localData().snapshot(schedule, "network")!!.fetchedUtc)
            } finally { db.close() }
        }
    }

    @Test fun calendarWrongYearDuplicateOrNonpositiveRoundAndTruncatedTotalKeepOldCacheAndFacts() = runBlocking {
        val archive = SportsArchive(instrumentation.targetContext)
        val url = "https://api.jolpi.ca/ergast/f1/2025.json?limit=100"
        val body = archive.response(url)!!.first
        val variants = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("MRData").getJSONObject("RaceTable").put("season", "2024") },
            { it.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0).put("season", "2024") },
            { val races = it.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races"); races.getJSONObject(1).put("round", races.getJSONObject(0).getString("round")) },
            { it.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0).put("round", "0") },
            { val root = it.getJSONObject("MRData"); root.put("total", root.getString("total").toInt() + 1) }
        )
        for (mutate in variants) {
            val db = database()
            try {
                val expected = F1Api.parseSeason(body, 2025)
                val store = FootballStore(db)
                store.writeRaces(2025, expected, oldTime)
                val before = store.f1Races(2025)
                cache(db, url, body)
                val invalid = JSONObject(body).apply(mutate).toString()
                assertTrue(runCatching { F1Api.parseSeason(invalid, 2025) }.isFailure)
                val response = JsonClient(database = db, transport = { invalid }).load(url) { F1Api.parseSeason(it, 2025) }
                assertNotNull(response.error)
                assertEquals(expected, response.data)
                store.writeRaces(2025, response.data, response.fetchedUtc)
                assertEquals(before, store.f1Races(2025))
                assertEquals(oldTime, store.sourceTime("f1:2025"))
                assertEquals(body, db.localData().snapshot(url, "network")!!.body)
                assertEquals(oldTime, db.localData().snapshot(url, "network")!!.fetchedUtc)
            } finally { db.close() }
        }
        val empty = JSONObject(body).apply {
            getJSONObject("MRData").put("total", "0").getJSONObject("RaceTable").put("Races", JSONArray())
        }.toString()
        assertTrue(F1Api.parseSeason(empty, 2025).isEmpty())
    }

    @Test fun standingsWrongIdentityDuplicatePositionsEntitiesOrTotalKeepOldCacheAndFacts() = runBlocking {
        val body = fixture("drivers.json")
        val url = "https://api.jolpi.ca/ergast/f1/2025/driverstandings.json?limit=100"
        fun list(root: JSONObject) = root.getJSONObject("MRData").getJSONObject("StandingsTable").getJSONArray("StandingsLists").getJSONObject(0)
        val variants = listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("MRData").getJSONObject("StandingsTable").put("season", "2024") },
            { list(it).put("season", "2024") },
            { list(it).getJSONArray("DriverStandings").getJSONObject(1).put("position", "1") },
            { list(it).getJSONArray("DriverStandings").getJSONObject(1).put("positionText", "1") },
            { list(it).getJSONArray("DriverStandings").getJSONObject(1).getJSONObject("Driver").put("driverId", "norris") },
            { list(it).getJSONArray("DriverStandings").getJSONObject(0).getJSONObject("Driver").remove("driverId") },
            { it.getJSONObject("MRData").put("total", "22") },
            { list(it).put("round", "23") }
        )
        for (mutate in variants) {
            val db = database()
            try {
                val expected = JolpicaAdapter.standings(body, false, 2025, 24)
                val store = FootballStore(db)
                store.writeStandings(2025, "driver", expected, oldTime)
                cache(db, url, body)
                val invalid = JSONObject(body).apply(mutate).toString()
                assertTrue(runCatching { JolpicaAdapter.standings(invalid, false, 2025, 24) }.isFailure)
                val response = JsonClient(database = db, transport = { invalid }).load(url) { JolpicaAdapter.standings(it, false, 2025, 24) }
                assertNotNull(response.error)
                store.writeStandings(2025, "driver", response.data, response.fetchedUtc)
                assertEquals(expected to oldTime, store.f1Standings(2025, "driver", "24"))
                assertEquals(body, db.localData().snapshot(url, "network")!!.body)
                assertEquals(oldTime, db.localData().snapshot(url, "network")!!.fetchedUtc)
            } finally { db.close() }
        }
        val constructorsUrl = "https://api.jolpi.ca/ergast/f1/2025/constructorstandings.json?limit=100"
        val constructors = SportsArchive(instrumentation.targetContext).response(constructorsUrl)!!.first
        val duplicate = JSONObject(constructors).apply {
            val rows = getJSONObject("MRData").getJSONObject("StandingsTable").getJSONArray("StandingsLists").getJSONObject(0).getJSONArray("ConstructorStandings")
            rows.getJSONObject(1).getJSONObject("Constructor").put("constructorId", rows.getJSONObject(0).getJSONObject("Constructor").getString("constructorId"))
        }.toString()
        assertTrue(runCatching { JolpicaAdapter.standings(duplicate, true, 2025) }.isFailure)
    }

    @Test fun qualifyingFailurePreservesErrorAndSuccessfulDownloadIsNotRelabeledCached() = runBlocking {
        val archive = SportsArchive(instrumentation.targetContext)
        val race = archive.read("https://api.jolpi.ca/ergast/f1/2025.json?limit=100") { F1Api.parseSeason(it, 2025) }!!.data.first()
        val url = "https://api.jolpi.ca/ergast/f1/2025/${race.round}/qualifying.json?limit=100"
        val body = archive.response(url)!!.first
        val db = database()
        try {
            cache(db, url, body)
            val expected = F1QualifyingRepository.parse(body, race)
            val failed = F1QualifyingRepository(JsonClient(database = db, transport = { throw IOException("offline") })).load(race, true)
            assertEquals(expected, failed.data)
            assertEquals(oldTime, failed.fetchedUtc)
            assertTrue(failed.cached)
            assertNotNull(failed.error)
            val success = F1QualifyingRepository(JsonClient(database = db, transport = { body })).load(race, true)
            assertEquals(expected, success.data)
            assertFalse(success.cached)
            assertFalse(success.archived)
            assertNull(success.error)
            assertNotEquals(oldTime, success.fetchedUtc)
        } finally { db.close() }
    }

    @Test fun rejectedOlderRosterReturnsEffectivePersistedMembersAndWholeSquadTime() = runBlocking {
        val archive = SportsArchive(instrumentation.targetContext)
        val url = archive.urls().map { it.first }.first { "/eng.1/teams/" in it && "/roster?season=2025" in it }
        val team = url.substringAfter("/teams/").substringBefore('/').toInt() + 1_000_000
        val body = archive.response(url)!!.first
        val parsed = EspnFootballAdapter.squad(body, 2025)
        assertTrue(parsed.players.size >= 2)
        val newer = Instant.now().plusSeconds(3600).toString()
        val accepted = parsed.copy(players = parsed.players.takeLast(2))
        val db = database()
        try {
            val store = FootballStore(db)
            store.writeSquad("eng.1", 2025, team, "Team", accepted, newer)
            val before = store.squad("eng.1", 2025, team)!!
            val repo = EuropeanFootballRepository(JsonClient(database = db, transport = { body }), store)
            val response = repo.squad(2025, "eng.1", team, force = true)
            assertEquals(before.first, response.data)
            assertEquals(newer, response.fetchedUtc)
            assertTrue(response.cached)
            assertEquals(before, store.squad("eng.1", 2025, team))
            store.writeSquad("eng.1", 2025, team, "Team", accepted.copy(players = emptyList()), Instant.parse(newer).plusSeconds(1).toString())
            val empty = repo.squad(2025, "eng.1", team)
            assertTrue(empty.data.players.isEmpty())
            assertEquals(Instant.parse(newer).plusSeconds(1).toString(), empty.fetchedUtc)
        } finally { db.close() }
    }

    @Test fun cancellationDuringValidParsingNeverCommitsTheNewResponse() = runBlocking {
        val db = database()
        val body = fixture("drivers.json")
        val url = "https://api.jolpi.ca/ergast/f1/2025/driverstandings.json?limit=100"
        try {
            cache(db, url, body)
            lateinit var request: Job
            request = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                JsonClient(database = db, transport = { body }).load(url) {
                    val parsed = JolpicaAdapter.standings(it, false, 2025)
                    request.cancel()
                    parsed
                }
            }
            request.start()
            request.join()
            assertTrue(request.isCancelled)
            withContext(Dispatchers.IO) {
                val snapshot = db.localData().snapshot(url, "network")!!
                assertEquals(body, snapshot.body)
                assertEquals(oldTime, snapshot.fetchedUtc)
            }
        } finally { db.close() }
    }

    @Test fun corruptDigestCannotShadowArchiveAndFreshResponseReplacesIt() = runBlocking {
        val archive = SportsArchive(instrumentation.targetContext)
        val url = "https://api.jolpi.ca/ergast/f1/2025.json?limit=100"
        val body = archive.response(url)!!.first
        val db = database()
        try {
            db.localData().putSnapshots(listOf(SourceSnapshotRow(
                url, "network", body, "2099-01-01T00:00:00Z", "0".repeat(64))))
            val databaseArchive = SportsArchive(instrumentation.targetContext, db)
            val offline = JsonClient(database = db, archive = databaseArchive,
                transport = { throw IOException("offline") })
            val fallback = offline.load(url) { F1Api.parseSeason(it, 2025) }
            assertEquals(archive.read(url) { F1Api.parseSeason(it, 2025) }!!.data, fallback.data)
            assertTrue(fallback.archived)

            val refreshed = JsonClient(database = db, archive = databaseArchive, transport = { body })
                .load(url) { F1Api.parseSeason(it, 2025) }
            assertFalse(refreshed.cached)
            assertFalse(refreshed.archived)
            assertNull(refreshed.error)
            val stored = db.localData().snapshot(url, "network")!!
            assertEquals(body, stored.body)
            assertEquals(LocalDataImporter.sha(body.toByteArray()), stored.sha256)
            assertNotEquals("2099-01-01T00:00:00Z", stored.fetchedUtc)
        } finally {
            db.close()
        }
    }

    @Test fun forcedF1CalendarRefreshPersistsRawEnvelopeAndAcquisitionTime() = runBlocking {
        val archive = SportsArchive(instrumentation.targetContext)
        val url = "https://api.jolpi.ca/ergast/f1/2025.json?limit=100"
        val body = archive.response(url)!!.first
        val db = database()
        try {
            val client = JsonClient(database = db, archive = SportsArchive(instrumentation.targetContext, db),
                transport = { body })
            val snapshot = JolpicaRaceRepository(client).snapshot(2025, force = true)
            assertFalse(snapshot.cached)
            assertFalse(snapshot.archived)
            val stored = db.localData().snapshot(url, "network")!!
            assertEquals(body, stored.body)
            assertEquals(snapshot.fetchedUtc, stored.fetchedUtc)
            assertEquals(LocalDataImporter.sha(body.toByteArray()), stored.sha256)
        } finally {
            db.close()
        }
    }
}
