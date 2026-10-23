package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeRefreshPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }

    @Test fun scopedDayRefreshCachesTheResponseButPreservesOtherFixturesEvenAfterReseeding() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val archive = SportsArchive(context, db)
            val store = FootballStore(db)
            val client = JsonClient(database = db, archive = archive, transport = { url ->
                when {
                    "scoreboard?dates=20260919" in url -> fixture("espn-day-overlay.json")
                    url == EspnFootballAdapter.standingsUrl("eng.1", 2026) -> fixture("espn-standings-six.json")
                    else -> error("Unexpected request $url")
                }
            })
            val seeder = DatabaseSeeder(db, store, archive, client)
            seeder.seedIfNeeded()
            val league = footballCompetitions.first { it.id == "eng.1" }
            val incoming = EspnFootballAdapter.matches(fixture("espn-day-overlay.json"), league, 2026)
            val target = incoming.first { it.id == "fb-espn-eng.1-401879269" }
            val historical = incoming.first { it.id != target.id }.copy(score = "9 : 9", status = "完场")
            val old = Instant.now().minusSeconds(60).toString()
            store.writeMatches(listOf(target.copy(score = "0 : 0", status = "待赛"), historical), league.id, league.title, 2026, old)
            val before = db.matches().freshness(listOf(historical.id)).single()
            assertTrue(EuropeanFootballRepository(client, store, archive).refreshDays(league.id, 2026, listOf("20260919"), setOf(target.id)))
            assertEquals("2 : 1", FootballStore(db).seasonMatches(2026).first { it.id == target.id }.score)
            assertEquals(historical.score, store.seasonMatches(2026).first { it.id == historical.id }.score)
            assertEquals(before, db.matches().freshness(listOf(historical.id)).single())
            assertNotNull(db.localData().snapshot(EspnFootballAdapter.dayUrl(league, "20260919"), "network"))
            db.meta().put(MetaRow("seedDataRevision", "test-reimport"))
            DatabaseSeeder(db, store, archive, client).seedIfNeeded()
            assertEquals("2 : 1", store.seasonMatches(2026).first { it.id == target.id }.score)
            assertEquals(historical.score, store.seasonMatches(2026).first { it.id == historical.id }.score)
            assertEquals(before, db.matches().freshness(listOf(historical.id)).single())
        } finally { db.close() }
    }

    @Test fun roundOnlyCalendarWritesKeepOtherStationsAndRejectAnOlderWholeSeasonOverwrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val store = FootballStore(db)
            val races = requireNotNull(SportsArchive(context).read("https://api.jolpi.ca/ergast/f1/2026.json?limit=100") { F1Api.parseSeason(it, 2026) }).data.take(3)
            store.writeRaces(2026, races, "2026-10-01T00:00:00Z")
            val beforeRows = db.f1().races(2026)
            val beforeSessions = db.f1().sessions(2026)
            val newer = races[1].copy(utcTime = "14:30:00Z")
            store.writeRaceRound(newer, "2026-10-20T00:00:00Z")
            assertEquals(beforeRows.filter { it.round != newer.round }, db.f1().races(2026).filter { it.round != newer.round })
            assertEquals(beforeSessions.filter { it.round != newer.round }, db.f1().sessions(2026).filter { it.round != newer.round })
            assertEquals(newer, store.f1Races(2026).first.first { it.round == newer.round })
            store.writeRaces(2026, races, "2026-10-10T00:00:00Z")
            assertEquals(newer, store.f1Races(2026).first.first { it.round == newer.round })
        } finally { db.close() }
    }

    @Test fun partialRoundRefreshKeepsPreviouslyStoredSessionTitles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val store = FootballStore(db)
            val races = requireNotNull(SportsArchive(context).read("https://api.jolpi.ca/ergast/f1/2026.json?limit=100") {
                F1Api.parseSeason(it, 2026)
            }).data.take(3)
            val originalTime = "2026-10-01T00:00:00Z"
            val refreshTime = "2026-10-20T00:00:00Z"
            store.writeRaces(2026, races, originalTime)
            val target = races.first { it.sessions.size >= 2 }
            val retained = db.f1().sessions(2026).filter {
                it.round == target.round && it.title != target.sessions.first().title
            }
            val partial = target.copy(sessions = target.sessions.take(1))

            store.writeRaceRound(partial, refreshTime)

            val sessions = db.f1().sessions(2026).filter { it.round == target.round }
            assertEquals(target.sessions.map { it.title }.toSet(), sessions.map { it.title }.toSet())
            assertEquals(retained, sessions.filter { it.title != target.sessions.first().title })
            assertEquals(refreshTime, sessions.single { it.title == target.sessions.first().title }.updatedAt)
            assertEquals(refreshTime, store.sourceTime("f1:2026:calendar:${target.round}"))
        } finally { db.close() }
    }
}
