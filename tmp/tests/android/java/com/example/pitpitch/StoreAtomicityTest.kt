package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoreAtomicityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val t1 = "2026-10-10T00:00:00Z"
    private val middle = "2026-10-11T00:00:00Z"
    private val t2 = "2026-10-12T00:00:00Z"
    private val t3 = "2026-10-13T00:00:00Z"
    private val detailUrl = "${EspnFootballAdapter.SITE}eng.1/summary?event=704467"
    private val match get() = sampleMatches.first().copy(id = "fb-espn-eng.1-704467", competitionId = "eng.1",
        homeTeamId = 1000337, awayTeamId = 1000359, isDemo = false)
    private fun detailBody() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("espn-match-detail.json").bufferedReader().use { it.readText() }
    private fun database() = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
    private suspend fun detailRows(db: PitPitchDatabase): List<Any> = listOf(
        db.matchDetails().teams(match.id), db.matchDetails().lineups(match.id),
        db.matchDetails().stats(match.id), db.matchDetails().events(match.id),
        db.sourceLog().byKind("detail"), db.sourceLog().byKind("detailSection"))
    private suspend fun mustAbort(action: suspend () -> Unit) {
        try { action(); fail("Injected SQLite ABORT must reach the caller") }
        catch (_: android.database.sqlite.SQLiteException) { }
    }

    @Test fun abortedDetailReplacementRetainsEveryTableAndAcquisitionClock() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val original = EspnMatchAdapter.detail(detailBody(), match)
            store.writeDetail(match.id, original, t2)
            val before = detailRows(db)
            // Events are inserted after teams, players and statistics have already been replaced.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_detail BEFORE INSERT ON matchEvents BEGIN SELECT RAISE(ABORT, 'detail fault'); END")
            mustAbort { store.writeDetail(match.id, original.copy(lineups = original.lineups.take(1),
                statistics = original.statistics.take(1), events = original.events.take(1)), t3) }
            assertEquals(before, detailRows(db))
            assertEquals(t2, store.sourceTime(detailUrl))
            assertEquals(original.events, store.detail(match)!!.events)
        } finally { db.close() }
    }

    @Test fun abortedCalendarReplacementRetainsRacesSessionsAndClock() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val races = SportsArchive(context).read("https://api.jolpi.ca/ergast/f1/2026.json?limit=100") {
                F1Api.parseSeason(it, 2026)
            }!!.data
            store.writeRaces(2026, races, t2)
            val before = Triple(db.f1().races(2026), db.f1().sessions(2026), store.f1Races(2026))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_session BEFORE INSERT ON f1Sessions BEGIN SELECT RAISE(ABORT, 'calendar fault'); END")
            mustAbort { store.writeRaces(2026, races.take(1), t3) }
            assertEquals(before, Triple(db.f1().races(2026), db.f1().sessions(2026), store.f1Races(2026)))
            assertEquals(t2, store.sourceTime("f1:2026"))
        } finally { db.close() }
    }

    @Test fun emptyProviderSnapshotsKeepCalendarResultsAndStandings() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val archive = SportsArchive(context)
            val urls = archive.urls().map { it.first }
            val races = archive.read(urls.first { it.contains("/f1/2026.json") }) { F1Api.parseSeason(it, 2026) }!!.data
            val results = archive.read(urls.first { it.contains("/f1/2026/1/results.json") }) { JolpicaAdapter.resultsForRound(it, 2026, 1) }!!.data
            val standings = archive.read(urls.first { it.contains("/f1/2026/driverstandings.json") }) { JolpicaAdapter.standings(it, false, 2026) }!!.data
            store.writeRaces(2026, races, t2)
            store.writeResults(2026, 1, results, t2)
            store.writeStandings(2026, "driver", standings, t2)
            val before = Triple(db.f1().races(2026), store.f1Results(2026, 1).first, store.f1Standings(2026, "driver", null)!!.first)
            store.writeRaces(2026, emptyList(), t3)
            store.writeResults(2026, 1, emptyList(), t3)
            store.writeStandings(2026, "driver", standings.copy(entries = emptyList()), t3)
            assertEquals(before, Triple(db.f1().races(2026), store.f1Results(2026, 1).first, store.f1Standings(2026, "driver", null)!!.first))
            assertEquals(t2, store.sourceTime("f1:2026"))
            assertEquals(t2, store.sourceTime("f1:2026:1"))
        } finally { db.close() }
    }

    @Test fun staleAndIncompleteDetailsPreserveFactsButExplicitCorrectionsReplaceThem() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val body = detailBody()
            val original = EspnMatchAdapter.detail(body, match)
            store.writeDetail(match.id, original, t2)
            val before = detailRows(db)
            store.writeDetail(match.id, original.copy(events = original.events.take(1)), t1)
            assertEquals(before, detailRows(db))
            val omitted = JSONObject(body).apply { remove("rosters"); remove("boxscore"); remove("keyEvents") }
            store.writeDetail(match.id, EspnMatchAdapter.detail(omitted.toString(), match), t3)
            assertEquals(before, detailRows(db))
            val partial = JSONObject(body).apply {
                put("rosters", JSONArray().put(getJSONArray("rosters").getJSONObject(0)))
                getJSONObject("boxscore").put("teams", JSONArray().put(getJSONObject("boxscore").getJSONArray("teams").getJSONObject(0)))
                remove("keyEvents")
            }
            store.writeDetail(match.id, EspnMatchAdapter.detail(partial.toString(), match), t3)
            assertEquals(before, detailRows(db))
            val corrected = original.copy(lineups = original.lineups.take(1), events = original.events.take(1))
            store.writeDetail(match.id, corrected, t3)
            assertEquals(corrected.lineups.map { it.teamId }, db.matchDetails().teams(match.id).map { it.teamId })
            assertEquals(corrected.events, store.detail(match)!!.events)
            assertEquals(t3, store.sourceTime(detailUrl))
            // Complete, explicitly empty provider arrays revoke previous facts, rather than representing omission.
            val empty = JSONObject(body).put("rosters", JSONArray()).put("keyEvents", JSONArray())
                .put("boxscore", JSONObject().put("teams", JSONArray()))
            val emptyTime = "2026-10-14T00:00:00Z"
            store.writeDetail(match.id, EspnMatchAdapter.detail(empty.toString(), match), emptyTime)
            assertTrue(db.matchDetails().teams(match.id).isEmpty())
            assertTrue(store.detail(match)!!.lineups.isEmpty())
            assertTrue(store.detail(match)!!.statistics.isEmpty())
            assertTrue(store.detail(match)!!.events.isEmpty())
            store.writeTimeline(match.id, listOf(MatchEvent(1, label = "scoreboard summary")), "2026-10-15T00:00:00Z")
            assertTrue(db.matchDetails().events(match.id).isEmpty())
            assertEquals(emptyTime, store.sourceTime(detailUrl))
        } finally { db.close() }
    }

    @Test fun squadReplacementRemovesDeparturesRejectsIntermediateTimeAndPersistsEmptySnapshot() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val archive = SportsArchive(context)
            val url = archive.urls().first { "/soccer/eng.1/teams/" in it.first && "/roster?season=2024" in it.first }.first
            val teamId = url.substringAfter("/teams/").substringBefore('/').toInt() + 1_000_000
            val squad = archive.read(url) { EspnFootballAdapter.squad(it, 2024) }!!.data
            val players = squad.players.take(3)
            assertEquals(3, players.size)
            store.writeSquad("eng.1", 2024, teamId, "Archive team", squad.copy(players = players.take(2)), t1)
            store.writeSquad("eng.1", 2024, teamId, "Archive team", squad.copy(players = players.drop(1)), t2)
            assertEquals(players.drop(1).map { it.id }.toSet(), store.squad("eng.1", 2024, teamId)!!.first.players.map { it.id }.toSet())
            store.writeSquad("eng.1", 2024, teamId, "Archive team", squad.copy(players = players), middle)
            assertEquals(players.drop(1).map { it.id }.toSet(), store.squad("eng.1", 2024, teamId)!!.first.players.map { it.id }.toSet())
            assertEquals(t2, db.players().squadTime("eng.1", 2024, teamId))
            assertEquals(t2, store.sourceTime("squad:eng.1:$teamId:2024"))
            val before = db.players().byTeam("eng.1", 2024, teamId)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_player BEFORE INSERT ON players BEGIN SELECT RAISE(ABORT, 'roster fault'); END")
            mustAbort { store.writeSquad("eng.1", 2024, teamId, "Archive team", squad.copy(players = players), t3) }
            assertEquals(before, db.players().byTeam("eng.1", 2024, teamId))
            assertEquals(t2, store.squad("eng.1", 2024, teamId)!!.second)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_player")
            store.writeSquad("eng.1", 2024, teamId, "Archive team", squad.copy(players = emptyList()), t3)
            val effective = store.squad("eng.1", 2024, teamId)!!
            assertTrue(effective.first.players.isEmpty())
            assertEquals(t3, effective.second)
            assertEquals(t3, db.players().squadTime("eng.1", 2024, teamId))
            assertEquals(t3, store.sourceTime(url))
            store.writeSquad("eng.1", 2024, teamId, "Archive team", squad, t2)
            assertTrue(store.squad("eng.1", 2024, teamId)!!.first.players.isEmpty())
        } finally { db.close() }
    }

    @Test fun legacySquadRowsUseNewestRetainedClockBeforeFirstCompleteReplacement() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val original = EspnMatchAdapter.detail(detailBody(), match).lineups.first().starters.take(2)
            val teamId = match.homeTeamId!!
            db.players().upsert(original.mapIndexed { index, player -> PlayerRow(player.id, 2024, "eng.1", teamId,
                "Archive team", player.name, player.position, player.jersey, player.appearances, player.goals, player.assists,
                player.photoUrl, if (index == 0) t1 else t2) })
            assertEquals(t2, store.squad("eng.1", 2024, teamId)!!.second)
            val old = FootballSquad(2024, original.take(1), null)
            store.writeSquad("eng.1", 2024, teamId, "Archive team", old, middle)
            assertEquals(2, store.squad("eng.1", 2024, teamId)!!.first.players.size)
            store.writeSquad("eng.1", 2024, teamId, "Archive team", old, t2)
            assertEquals(original.take(1).map { it.id }, store.squad("eng.1", 2024, teamId)!!.first.players.map { it.id })
        } finally { db.close() }
    }

    @Test fun overlappingNewAndOldWritesCannotRegressMatchSourceOrTimeline() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val resource = "${EspnFootballAdapter.SITE}eng.1/scoreboard?dates=2025&limit=1000"
            val newMatch = match.copy(score = "3 : 0")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val newer = async {
                    db.transaction {
                        store.writeMatches(listOf(newMatch), "eng.1", "英超", 2024, t2, resource)
                        store.writeTimeline(match.id, listOf(MatchEvent(3, label = "new fact")), t2, resource)
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                // UNDISPATCHED reaches the suspended Store call while the newer Room transaction is held.
                val older = async(start = CoroutineStart.UNDISPATCHED) {
                    store.writeMatches(listOf(match.copy(score = "1 : 0")), "eng.1", "英超", 2024, t1, resource)
                    store.writeTimeline(match.id, listOf(MatchEvent(1, label = "old fact")), t1, resource)
                    store.writeSource(resource, t1, "schedule")
                }
                release.complete(Unit)
                newer.await(); older.await()
            }
            assertEquals("3 : 0", store.seasonMatches(2024).single().score)
            assertEquals(t2, db.matches().freshness(listOf(match.id)).single().updatedAt)
            assertEquals(t2, store.sourceTime(resource))
            assertEquals(listOf("new fact"), db.matchDetails().events(match.id).map { it.label })
        } finally { db.close() }
    }

    @Test fun overlappingOldDetailCalendarAndSquadCannotReplaceNewSnapshots() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val original = EspnMatchAdapter.detail(detailBody(), match)
            val corrected = original.copy(events = original.events.take(1))
            val races = SportsArchive(context).read("https://api.jolpi.ca/ergast/f1/2026.json?limit=100") {
                F1Api.parseSeason(it, 2026)
            }!!.data
            val players = original.lineups.first().starters.take(3)
            val team = match.homeTeamId!!
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val newer = async {
                    db.transaction {
                        store.writeDetail(match.id, corrected, t2)
                        store.writeRaces(2026, races.take(1), t2)
                        store.writeSquad("eng.1", 2024, team, "Archive team", FootballSquad(2024, players.drop(1)), t2)
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                val older = async(start = CoroutineStart.UNDISPATCHED) {
                    store.writeDetail(match.id, original, t1)
                    store.writeRaces(2026, races, t1)
                    store.writeSquad("eng.1", 2024, team, "Archive team", FootballSquad(2024, players), t1)
                }
                release.complete(Unit)
                newer.await(); older.await()
            }
            assertEquals(corrected.events, store.detail(match)!!.events)
            assertEquals(t2, store.sourceTime(detailUrl))
            assertEquals(races.take(1).map { it.round }, store.f1Races(2026).first.map { it.round })
            assertEquals(t2, store.f1Races(2026).second)
            assertEquals(players.drop(1).map { it.id }.toSet(), store.squad("eng.1", 2024, team)!!.first.players.map { it.id }.toSet())
            assertEquals(t2, store.squad("eng.1", 2024, team)!!.second)
        } finally { db.close() }
    }

    @Test fun failedMatchSourceAndSummaryInsertionRollBackTheirFactsAndClocks() = runBlocking {
        val db = database()
        try {
            val store = FootballStore(db)
            val url = "${EspnFootballAdapter.SITE}eng.1/scoreboard?dates=2025&limit=1000"
            store.writeMatches(listOf(match.copy(score = "1 : 0")), "eng.1", "英超", 2024, t1, url)
            store.writeTimeline(match.id, listOf(MatchEvent(1, label = "previous")), t1, url)
            val previousMatches = db.matches().bySeason(2024)
            val previousEvents = db.matchDetails().events(match.id)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_source_insert BEFORE INSERT ON sourceLog WHEN NEW.url = '$url' AND NEW.fetchedUtc = '$t2' BEGIN SELECT RAISE(ABORT, 'source fault'); END")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_source_update BEFORE UPDATE ON sourceLog WHEN NEW.url = '$url' AND NEW.fetchedUtc = '$t2' BEGIN SELECT RAISE(ABORT, 'source fault'); END")
            mustAbort { store.writeMatches(listOf(match.copy(score = "3 : 0")), "eng.1", "英超", 2024, t2, url) }
            assertEquals(previousMatches, db.matches().bySeason(2024))
            assertEquals(t1, store.sourceTime(url))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_summary BEFORE INSERT ON matchEvents BEGIN SELECT RAISE(ABORT, 'summary fault'); END")
            mustAbort { store.writeTimeline(match.id, listOf(MatchEvent(3, label = "replacement")), t2, url) }
            assertEquals(previousEvents, db.matchDetails().events(match.id))
            assertEquals(t1, store.sourceTime("timeline:${match.id}"))
            assertEquals(t1, store.sourceTime(url))
        } finally { db.close() }
    }
}
