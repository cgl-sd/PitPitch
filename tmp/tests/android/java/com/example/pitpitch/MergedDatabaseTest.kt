package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MergedDatabaseTest {
    @Test fun matchingLocalResponseRestoresFullEventTextWithoutChangingFacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val archive = SportsArchive(context)
            val league = footballCompetitions.first { it.id == "eng.1" }
            val match = archive.read("${EspnFootballAdapter.SITE}eng.1/scoreboard?dates=2025&limit=1000") {
                EspnFootballAdapter.matches(it, league, 2024)
            }!!.data.first { it.id.endsWith("-704467") }
            val body = InstrumentationRegistry.getInstrumentation().context.assets.open("espn-match-detail.json").bufferedReader().use { it.readText() }
            val original = EspnMatchAdapter.detail(body, match)
            val legacy = original.copy(events = original.events.map { if (it.type.startsWith("goal")) it.copy(text = "Goal") else it })
            val url = "${EspnFootballAdapter.SITE}eng.1/summary?event=704467"
            val stamp = "2026-10-11T00:00:00Z"
            val store = FootballStore(db)
            store.writeSource(url, stamp, "detail")
            db.localData().putSnapshots(listOf(SourceSnapshotRow(url, "network", body, stamp, LocalDataImporter.sha(body.toByteArray()))))
            var requests = 0
            val repo = EuropeanFootballRepository(JsonClient(database = db, transport = { requests++; error("Unexpected network") }), store, archive)
            val started = System.nanoTime()
            assertEquals(original.events, repo.matchDetail(match).data.events)
            android.util.Log.i("PitPitchReadTest", "cachedSummaryMs=${(System.nanoTime() - started) / 1_000_000}")
            assertEquals(0, requests)
            store.writeDetail(match.id, legacy, stamp)
            assertEquals(original.events, repo.matchDetail(match).data.events)
            assertEquals(original.events, store.detail(match)!!.events)
            store.writeDetail(match.id, legacy, "2026-10-12T00:00:00Z")
            store.writeSource(url, "2026-10-12T00:00:00Z", "detail")
            assertEquals(legacy.events, repo.matchDetail(match).data.events)
            assertEquals(0, requests)
        } finally { db.close() }
    }
    @Test fun databasePreservesBundledRoundRulesMediaIdsAndDetailedEvents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val archive = SportsArchive(context); val store = FootballStore(db)
            db.meta().put(MetaRow("legacyHttpImported", "1"))
            db.meta().put(MetaRow("legacyCalendarImported", "1"))
            DatabaseSeeder(db, store, archive).seedIfNeeded()
            assertEquals("0", db.meta().get("seedFailures"))
            val repo = EuropeanFootballRepository(JsonClient(), store, archive)
            val season = repo.season(2025)
            assertTrue(season.matches.any { it.competitionId == "eng.1" && it.roundNumber != null })
            val table = season.tables.getValue("eng.1").data.single()
            assertTrue(table.entries.all { it.team.badgeUrl != null })
            assertEquals(5, table.entries.count { standingMark(it, table, "eng.1", 2025).zone == TableZone.CHAMPIONS })
            val ranks = store.f1Standings(2026, "driver", null)!!.first
            assertEquals("16", ranks.round)
            assertTrue(ranks.entries.all { it.driverId != null })
            assertTrue(store.f1Standings(2026, "constructor", null)!!.first.entries.all { it.constructorId != null })
            assertTrue(store.f1Results(2020, 1).first.all { it.driverId != null && it.grid != null })
            val match = repo.season(2024).matches.first { it.id.endsWith("-704467") }
            val body = InstrumentationRegistry.getInstrumentation().context.assets.open("espn-match-detail.json").bufferedReader().use { it.readText() }
            val detail = EspnMatchAdapter.detail(body, match)
            store.writeDetail(match.id, detail, "2026-10-11T00:00:00Z")
            store.writeTimeline(match.id, listOf(MatchEvent(1, 0, "summary")), "2026-10-11T01:00:00Z")
            assertEquals(detail.events, store.detail(match)!!.events)
            assertEquals(detail.statistics.toSet(), store.detail(match)!!.statistics.toSet())
            assertTrue(store.detail(match)!!.events.any { it.teamId == match.homeTeamId || it.teamId == match.awayTeamId })
        } finally { db.close() }
    }
    @Test fun newerEmptyCalendarAndMatchRowsSurviveOlderBundledWrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val archive = SportsArchive(context); val store = FootballStore(db)
            val source = archive.read("https://api.jolpi.ca/ergast/f1/2026.json?limit=100") { F1Api.parseSeason(it, 2026) }!!
            val newer = Instant.parse(source.fetchedUtc).plusSeconds(60).toString()
            store.writeRaces(2026, emptyList(), newer)
            store.writeRaces(2026, source.data, source.fetchedUtc)
            assertTrue(store.f1Races(2026).first.isEmpty())
            assertEquals(newer, store.f1Races(2026).second)
            val league = footballCompetitions.first { it.id == "eng.1" }
            val snapshot = archive.read(EspnFootballAdapter.scheduleUrl(league.id, 2026)) { EspnFootballAdapter.matches(it, league, 2026) }!!
            store.writeMatches(snapshot.data, league.id, league.title, 2026, newer)
            store.writeMatches(snapshot.data, league.id, league.title, 2026, source.fetchedUtc)
            assertTrue(db.matches().freshness(snapshot.data.map { it.id }).all { it.updatedAt == newer })
        } finally { db.close() }
    }
}
