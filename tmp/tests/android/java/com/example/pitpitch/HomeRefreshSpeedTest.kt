package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Gates establish request ordering without relying on internet speed or timing thresholds. */
class HomeRefreshSpeedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun bundled(url: String): String {
        val catalog = JSONObject(context.assets.open("sports/catalog.json").bufferedReader().use { it.readText() })
        return context.assets.open("sports/${catalog.getJSONObject(url).getString("file")}").bufferedReader().use { it.readText() }
    }

    @Test fun fastDateIsSavedAndPublishedBeforeASlowDateInTheSameLeague() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        val releaseSlow = CountDownLatch(1)
        var task: Deferred<HomeDataRefresh>? = null
        try {
            val store = FootballStore(db)
            val league = footballCompetitions.first { it.id == "eng.1" }
            val fastBody = fixture("espn-city-finished-day.json")
            val season = JSONObject(bundled(EspnFootballAdapter.scheduleUrl(league.id, 2026)))
            val events = season.getJSONArray("events")
            val slowEvent = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("id") == "401879276" }
            val slowBody = JSONObject().put("events", JSONArray().put(slowEvent)).toString()
            val fast = EspnFootballAdapter.matches(fastBody, league, 2026).single()
            val slow = EspnFootballAdapter.matches(slowBody, league, 2026).single()
            store.writeMatches(listOf(fast, slow).map { it.copy(status = "待赛", score = "— : —") },
                league.id, league.title, 2026, "2026-01-01T00:00:00Z")
            val partial = CompletableDeferred<List<FootballMatch>>()
            val requests = ConcurrentHashMap<String, AtomicInteger>()
            val client = JsonClient(database = db, transport = { url ->
                requests.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
                when {
                    "dates=20260919" in url -> fastBody
                    "dates=20260920" in url -> {
                        check(releaseSlow.await(10, TimeUnit.SECONDS))
                        slowBody
                    }
                    else -> error("Statistics deliberately unavailable in this schedule-only fixture")
                }
            })
            val football = EuropeanFootballRepository(client, store)
            val home = HomeDataRefreshRepository(football, store, client)
            val hidden = footballCompetitions.filter { it != league }.map { it.id }.toSet() + "f1"
            task = async(Dispatchers.Default) {
                home.refresh(2026, hidden, Instant.parse("2026-09-20T18:00:00Z"), ZoneOffset.UTC,
                    onFootballUpdated = { partial.complete(football.seasonMatches(2026)) })
            }
            val published = withTimeout(5000) { partial.await() }
            assertEquals(fast.score, published.single { it.id == fast.id }.score)
            assertEquals("待赛", published.single { it.id == slow.id }.status)
            assertEquals(fast.score, store.seasonMatches(2026).single { it.id == fast.id }.score)
            assertFalse(task.isCompleted)
            assertNull(store.homeUpdated())
            releaseSlow.countDown()
            val complete = withTimeout(5000) { task.await() }
            assertEquals(slow.score, complete.football.matches.single { it.id == slow.id }.score)
            assertEquals(1, requests[EspnFootballAdapter.dayUrl(league, "20260919")]!!.get())
            assertEquals(1, requests[EspnFootballAdapter.dayUrl(league, "20260920")]!!.get())
        } finally {
            releaseSlow.countDown()
            task?.cancelAndJoin()
            db.close()
        }
    }

    @Test fun scoresPublishWhileRostersWaitAndStandingsAndNewTeamsAreRequestedOnce() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        val releaseTable = CountDownLatch(1)
        val releaseScores = CountDownLatch(1)
        val releaseRosters = CountDownLatch(1)
        var task: Deferred<HomeDataRefresh>? = null
        try {
            val store = FootballStore(db)
            val league = footballCompetitions.first { it.id == "eng.1" }
            val scoreBody = fixture("espn-city-finished-day.json")
            val match = EspnFootballAdapter.matches(scoreBody, league, 2026).single()
            val oldTime = "2026-01-01T00:00:00Z"
            store.writeMatches(listOf(match.copy(status = "待赛", score = "— : —")), league.id, league.title, 2026, oldTime)
            store.writeStandings(league.id, 2026, EspnFootballAdapter.tables(fixture("espn-standings-five.json"), 2026), oldTime)
            val newTable = JSONObject(fixture("espn-standings-six.json"))
            val entries = newTable.getJSONObject("standings").getJSONArray("entries")
            val added = JSONObject(entries.getJSONObject(0).toString()).put("team", JSONObject().put("id", "349").put("displayName", "Ipswich Town"))
            entries.put(added)
            val tableUrl = EspnFootballAdapter.standingsUrl(league.id, 2026)
            val tableRequests = AtomicInteger()
            val rosterRequests = ConcurrentHashMap<String, AtomicInteger>()
            val squadUpdates = ConcurrentHashMap<String, RemoteSnapshot<FootballSquad>>()
            val firstRosterStarted = CompletableDeferred<Unit>()
            val addedRosterStarted = CompletableDeferred<Unit>()
            val publishedScores = CompletableDeferred<List<FootballMatch>>()
            val client = JsonClient(database = db, transport = { url ->
                when {
                    url == tableUrl -> {
                        tableRequests.incrementAndGet()
                        check(releaseTable.await(10, TimeUnit.SECONDS)) { "Table gate timed out" }
                        newTable.toString()
                    }
                    "/scoreboard?" in url -> {
                        check(releaseScores.await(10, TimeUnit.SECONDS)) { "Score gate timed out" }
                        scoreBody
                    }
                    "/roster?" in url -> {
                        rosterRequests.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
                        if ("/teams/382/" in url) firstRosterStarted.complete(Unit)
                        if ("/teams/349/" in url) addedRosterStarted.complete(Unit)
                        check(releaseRosters.await(10, TimeUnit.SECONDS)) { "Roster gate timed out" }
                        bundled(url)
                    }
                    else -> error("Unexpected request $url")
                }
            })
            val football = EuropeanFootballRepository(client, store)
            val home = HomeDataRefreshRepository(football, store, client)
            val hidden = footballCompetitions.filter { it != league }.map { it.id }.toSet() + "f1"
            task = async(Dispatchers.Default) {
                home.refresh(2026, hidden, Instant.parse("2026-09-19T18:00:00Z"), ZoneOffset.UTC,
                    onFootballUpdated = { publishedScores.complete(football.seasonMatches(2026)) },
                    onSquadUpdated = { key, snapshot -> squadUpdates[key] = snapshot })
            }
            // The known team's roster must start before the blocked standings response is released.
            withTimeout(5000) { firstRosterStarted.await() }
            assertFalse(task.isCompleted)
            releaseTable.countDown()
            // A team only present in the new table joins the batch, once, without restarting known teams.
            withTimeout(5000) { addedRosterStarted.await() }
            releaseScores.countDown()
            val partial = withTimeout(5000) { publishedScores.await() }.single()
            assertEquals("3 : 1", partial.score)
            assertEquals("完场", partial.status)
            assertFalse("Score publication must not wait for rosters", task.isCompleted)
            assertNull("An unfinished refresh must not advance its completion time", store.homeUpdated())
            releaseRosters.countDown()
            val result = withTimeout(5000) { task.await() }
            assertEquals(1, tableRequests.get())
            assertEquals(2, rosterRequests.size)
            assertTrue(rosterRequests.values.all { it.get() == 1 })
            assertEquals(setOf("2026|eng.1|1000382", "2026|eng.1|1000349"), squadUpdates.keys)
            for ((key, snapshot) in squadUpdates) {
                val stored = store.squad("eng.1", 2026, key.substringAfterLast('|').toInt())!!
                assertEquals(stored.first, snapshot.data)
                assertEquals(stored.second, snapshot.fetchedUtc)
            }
            assertNotNull(result.updatedUtc)
            assertEquals(result.updatedUtc, store.homeUpdated())
            assertEquals("3 : 1", result.football.matches.single().score)
        } finally {
            releaseTable.countDown(); releaseScores.countDown(); releaseRosters.countDown()
            task?.cancelAndJoin()
            db.close()
        }
    }
}
