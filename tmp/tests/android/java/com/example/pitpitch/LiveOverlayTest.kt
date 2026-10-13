package com.example.pitpitch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.FootballStore
import com.example.pitpitch.data.db.PitPitchDatabase
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Day-level refresh writes into the database; later writes from live fetches are what the UI reads. */
@RunWith(AndroidJUnit4::class)
class LiveOverlayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }

    @Test fun dayScoreboardKeepsProviderLiveStatusAndFinalScore() {
        val league = footballCompetitions.first { it.id == "eng.1" }
        val matches = EspnFootballAdapter.matches(fixture("espn-day-overlay.json"), league, 2026)
        assertEquals(2, matches.size)
        val live = matches.first { it.id == "fb-espn-eng.1-401879269" }
        assertEquals("进行中", live.status); assertEquals("2 : 1", live.score)
        val finished = matches.first { it.id == "fb-espn-eng.1-401879274" }
        assertEquals("完场", finished.status); assertNull(finished.liveDetail)
    }

    @Test fun dayRefreshWritesDatabaseAndSubsequentReadsSeeLiveState() = runBlocking {
        val context = instrumentation.targetContext
        val dayBody = fixture("espn-day-overlay.json")
        val client = JsonClient(transport = { url ->
            val day = url.substringAfter("dates=").substringBefore('&')
            if (url.contains("/scoreboard?dates=") && day.length == 8) dayBody
            else if (url == EspnFootballAdapter.standingsUrl("eng.1", 2026)) fixture("espn-standings-six.json")
            else throw IllegalStateException("Unexpected network request in test: $url")
        })
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val store = FootballStore(db)
            val repo = EuropeanFootballRepository(client, store)
            val baseline = listOf(FootballMatch(
                id = "fb-espn-eng.1-401879269", competition = "英超", stage = "常规联赛", home = "Tottenham Hotspur FC",
                away = "Aston Villa FC", homeShort = "Tottenham", awayShort = "Aston Villa", score = "1 : 0", status = "完场",
                stadium = "Tottenham Hotspur Stadium", date = "2026-09-19", kickoffUtc = "2026-09-19T11:30:00Z",
                isDemo = false, homeTeamId = 1000359, awayTeamId = 1000362, competitionId = "eng.1"))
            store.writeMatches(baseline, "eng.1", "英超", 2026, "2026-01-01T00:00:00Z")
            assertEquals("完场", store.seasonMatches(2026).first { it.id == "fb-espn-eng.1-401879269" }.status)
            assertTrue(repo.refreshDays("eng.1", 2026, listOf("20260919")))
            val stored = store.seasonMatches(2026).first { it.id == "fb-espn-eng.1-401879269" }
            assertEquals("进行中", stored.status); assertEquals("2 : 1", stored.score)
        } finally {
            db.close()
        }
    }
}
