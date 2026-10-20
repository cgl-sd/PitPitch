package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** The whole refresh uses isolated Room data and deterministic provider responses, without HTTP. */
class HomeRoundRefreshTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog by lazy { JSONObject(context.assets.open("sports/catalog.json").bufferedReader().use { it.readText() }) }
    private fun bundled(url: String) = context.assets.open("sports/${catalog.getJSONObject(url).getString("file")}")
        .bufferedReader().use { it.readText() }

    @Test fun nextRoundRefreshAlsoCachesSeasonTotalsAndPersistsOnlySuccessfulUpdateTime() = runBlocking {
        val name = "test-home-refresh-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, PitPitchDatabase::class.java, name).build()
        try {
            val archive = SportsArchive(context, db)
            val store = FootballStore(db)
            DatabaseSeeder(db, store, archive, JsonClient(archive = archive, database = db, transport = { error("No HTTP during seed") })).seedIfNeeded()
            val year = 2026
            val zone = ZoneId.of("Asia/Shanghai")
            val now = Instant.parse("2026-10-20T00:00:00Z")
            val hidden = footballCompetitions.filter { it.id != "eng.1" }.map { it.id }.toSet()
            val original = store.seasonMatches(year)
            val history = original.first { it.competitionId == "eng.1" && it.status == "完场" }
            val historyRow = db.matches().freshness(listOf(history.id)).single()
            val targets = homeRefreshMatches(original, hidden, now, zone).map { it.id.substringAfterLast('-') }.toSet()
            val seasonBody = JSONObject(bundled(EspnFootballAdapter.scheduleUrl("eng.1", year)))
            val allEvents = seasonBody.getJSONArray("events")
            val upcoming = (0 until allEvents.length()).map { allEvents.getJSONObject(it) }
                .filter { it.getString("id") in targets || it.getString("id") == history.id.substringAfterLast('-') }
            val scoreboard = seasonBody.put("events", JSONArray(upcoming)).toString()
            val tableUrl = EspnFootballAdapter.standingsUrl("eng.1", year)
            val ranking = JSONObject(bundled(tableUrl))
            val tableEntry = ranking.getJSONArray("children").getJSONObject(0).getJSONObject("standings").getJSONArray("entries").getJSONObject(0)
            val stats = tableEntry.getJSONArray("stats")
            for (i in 0 until stats.length()) if (stats.getJSONObject(i).getString("name") == "points")
                stats.getJSONObject(i).put("value", 55).put("displayValue", "55")
            val teamId = tableEntry.getJSONObject("team").getString("id")
            val rosterUrl = "${EspnFootballAdapter.SITE}eng.1/teams/$teamId/roster?season=$year"
            val roster = JSONObject(bundled(rosterUrl))
            val athletes = roster.getJSONArray("athletes")
            val athlete = (0 until athletes.length()).map { athletes.getJSONObject(it) }
                .first { it.optJSONObject("statistics")?.optJSONObject("splits")?.optJSONArray("categories") != null }
            val categories = athlete.getJSONObject("statistics").getJSONObject("splits").getJSONArray("categories")
            for (i in 0 until categories.length()) {
                val values = categories.getJSONObject(i).getJSONArray("stats")
                for (j in 0 until values.length()) {
                    val stat = values.getJSONObject(j)
                    if (stat.getString("name") == "totalGoals") stat.put("value", 37).put("displayValue", "37")
                    if (stat.getString("name") == "goalAssists") stat.put("value", 19).put("displayValue", "19")
                }
            }
            val requests = ConcurrentLinkedQueue<String>()
            val active = AtomicInteger(); val maximum = AtomicInteger()
            var fail = false
            val client = JsonClient(archive = archive, database = db, transport = { url ->
                requests.add(url)
                if (fail) error("offline")
                when {
                    url == tableUrl -> ranking.toString()
                    "/eng.1/scoreboard?dates=" in url -> scoreboard
                    "/roster?" in url -> {
                        val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
                        try { Thread.sleep(15); if (url == rosterUrl) roster.toString() else bundled(url) }
                        finally { active.decrementAndGet() }
                    }
                    Regex("https://api.jolpi.ca/ergast/f1/2026/\\d+\\.json\\?limit=100").matches(url) -> {
                        val full = JSONObject(bundled("https://api.jolpi.ca/ergast/f1/2026.json?limit=100"))
                        val table = full.getJSONObject("MRData").put("total", "1").getJSONObject("RaceTable")
                        val races = table.getJSONArray("Races")
                        val round = url.substringAfter("2026/").substringBefore('.'); table.put("round", round)
                        table.put("Races", JSONArray((0 until races.length()).map { races.getJSONObject(it) }.filter { it.getString("round") == round }))
                        full.toString()
                    }
                    else -> bundled(url)
                }
            })
            val repository = HomeDataRefreshRepository(EuropeanFootballRepository(client, store, archive), store, client)
            val result = repository.refresh(year, hidden, now, zone)
            assertEquals("55", result.football.tables.getValue("eng.1").data.first().entries.first().points)
            val player = result.leaderboards.getValue("2026|eng.1").data.first { it.playerId == athlete.getString("id") }
            assertEquals(37, player.goals); assertEquals(19, player.assists)
            assertNotNull(result.races); assertNotNull(result.drivers); assertNotNull(result.constructors)
            assertTrue(requests.contains("https://api.jolpi.ca/ergast/f1/2026/driverstandings.json?limit=100"))
            assertTrue(requests.contains("https://api.jolpi.ca/ergast/f1/2026/constructorstandings.json?limit=100"))
            assertTrue(maximum.get() <= 4)
            assertTrue(requests.none { "/eng.1/scoreboard?dates=2026&" in it || "season=2025" in it || "/f1/2025/" in it })
            assertEquals(historyRow, db.matches().freshness(listOf(history.id)).single())
            val successful = requireNotNull(result.updatedUtc)
            assertEquals(successful, FootballStore(db).homeUpdated())
            fail = true
            val offline = repository.refresh(year, hidden, now, zone)
            assertEquals(successful, offline.updatedUtc)
            assertEquals(37, offline.leaderboards.getValue("2026|eng.1").data.first { it.playerId == athlete.getString("id") }.goals)
            store.recordHomeUpdated("2000-01-01T00:00:00Z")
            assertEquals(successful, store.homeUpdated())
            db.close()
            db = Room.databaseBuilder(context, PitPitchDatabase::class.java, name).build()
            assertEquals(successful, FootballStore(db).homeUpdated())
            assertEquals(37, FootballStore(db).leaderboards("eng.1", year).first.first { it.playerId == athlete.getString("id") }.goals)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
