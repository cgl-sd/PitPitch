package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Real APK inputs plus an isolated pre-upgrade Room database; never installed user caches. */
class UpgradeLocalFirstTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun row(url: String, origin: String, body: String, time: String) =
        SourceSnapshotRow(url, origin, body, time, LocalDataImporter.sha(body.toByteArray(Charsets.UTF_8)))
    private suspend fun isolateLegacy(db: PitPitchDatabase) {
        db.meta().put(MetaRow("legacyHttpImported", "1"))
        db.meta().put(MetaRow("legacyCalendarImported", "1"))
    }

    @Test fun apkUpgradePreservesConflictsDetailsAndPreferencesWhileAddingMissingFactsAndRepairingInvalidInputs() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        val prefsName = "upgrade-local-first-isolated"
        val prefs = context.getSharedPreferences(prefsName, 0)
        prefs.edit().clear().putString("selectedLeague", "esp.1").putBoolean("reminderEnabled", true)
            .putString("f1_calendar_2026", "keep-user-cache").commit()
        val preferencesBefore = prefs.all.toMap()
        try {
            isolateLegacy(db)
            val apk = SportsArchive(context)
            val calendarUrl = "https://api.jolpi.ca/ergast/f1/2026.json?limit=100"
            val calendar = apk.read(calendarUrl) { F1Api.parseSeason(it, 2026) }!!
            val oldTime = Instant.parse(calendar.fetchedUtc).minusSeconds(3600).toString()
            val oldBody = JSONObject(apk.response(calendarUrl)!!.first).apply {
                val root = getJSONObject("MRData")
                val races = root.getJSONObject("RaceTable").getJSONArray("Races")
                val first = races.getJSONObject(0).put("raceName", "Retained device race")
                root.put("total", "1").getJSONObject("RaceTable").put("Races", JSONArray().put(first))
            }.toString()
            val retainedResponse = row(calendarUrl, "bundled", oldBody, oldTime)
            val invalidUrl = "https://api.jolpi.ca/ergast/f1/2020.json?limit=100"
            // Syntactically valid JSON with invalid provider structure must be repaired too.
            db.localData().putSnapshots(listOf(retainedResponse, row(invalidUrl, "bundled", "{}", "2099-01-01T00:00:00Z")))
            val store = FootballStore(db)
            store.writeRaces(2026, F1Api.parseSeason(oldBody, 2026), oldTime)
            val retainedRace = db.f1().races(2026).single()
            val retainedSessions = db.f1().sessions(2026)
            val invalidRace = calendar.data.first { it.round != retainedRace.round }
            db.f1().upsertRaces(listOf(F1RaceRow(2026, invalidRace.round, "Corrupt local row", invalidRace.circuit,
                invalidRace.country, invalidRace.date, invalidRace.utcTime, invalidRace.circuitId, "invalid acquisition time")))
            val league = footballCompetitions.first { it.id == "eng.1" }
            val scheduleUrl = EspnFootballAdapter.scheduleUrl(league.id, 2025)
            val matches = apk.read(scheduleUrl) { EspnFootballAdapter.matches(it, league, 2024) }!!.data
            val originalMatch = matches.first { it.id.endsWith("-704467") }
            store.writeMatches(listOf(originalMatch.copy(score = "9 : 8")), league.id, league.title, 2024, oldTime, scheduleUrl)
            val retainedMatch = db.matches().bySeason(2024).single()
            val detailBody = instrumentation.context.assets.open("espn-match-detail.json").bufferedReader().use { it.readText() }
            val detail = EspnMatchAdapter.detail(detailBody, originalMatch)
            store.writeDetail(originalMatch.id, detail, oldTime)
            val detailBefore = store.detail(originalMatch)
            val rosterUrl = apk.urls().map { it.first }.first { "/eng.1/teams/" in it && "/roster?season=2024" in it }
            val team = rosterUrl.substringAfter("/teams/").substringBefore('/').toInt() + 1_000_000
            val roster = apk.read(rosterUrl) { EspnFootballAdapter.squad(it, 2024) }!!.data
            store.writeSquad(league.id, 2024, team, "Local club", roster.copy(players = roster.players.take(1)), oldTime)
            val retainedPlayer = db.players().byTeam(league.id, 2024, team).single()
            // A future source time with corrupt JSON must not shadow the real valid APK repair.
            val oldRosterBody = JSONObject(apk.response(rosterUrl)!!.first).apply {
                put("athletes", JSONArray().put(getJSONArray("athletes").getJSONObject(0)))
            }.toString()
            db.localData().putSnapshots(listOf(row(rosterUrl, "bundled", oldRosterBody, oldTime),
                row(rosterUrl, "network", "broken JSON", "2099-01-01T00:00:00Z")))
            db.meta().put(MetaRow("localDataRevision", "previous-apk-revision"))
            db.meta().put(MetaRow("seedVersion", "${PitPitchDatabase.SEED_VERSION}:atomic-2"))
            LocalDataImporter(context, db, prefs, RaceCache(prefs)).importIfNeeded()
            val archive = SportsArchive(context, db)
            val client = JsonClient(database = db, archive = archive, transport = { error("Bootstrap must remain offline") })
            DatabaseSeeder(db, store, archive, client).seedIfNeeded()
            assertEquals("0", db.meta().get("seedFailures"))
            assertEquals(DatabaseSeeder.SEED_VERSION, db.meta().get("seedVersion"))
            withContext(Dispatchers.IO) {
                assertEquals(retainedResponse, db.localData().snapshot(calendarUrl, "bundled"))
                assertEquals(oldBody to oldTime, archive.response(calendarUrl))
                assertEquals(apk.response(invalidUrl), archive.response(invalidUrl))
                assertNotNull(db.localData().snapshot(invalidUrl, "bundled"))
                assertEquals(oldRosterBody to oldTime, archive.response(rosterUrl))
            }
            assertEquals(retainedRace, db.f1().races(2026).first { it.round == retainedRace.round })
            assertTrue(db.f1().sessions(2026).containsAll(retainedSessions))
            assertEquals(calendar.data.map { it.round }.toSet(), db.f1().races(2026).map { it.round }.toSet())
            assertTrue(db.f1().races(2026).filter { it.round != retainedRace.round }.all { it.updatedAt == calendar.fetchedUtc })
            assertEquals(retainedMatch, db.matches().bySeason(2024).first { it.matchId == retainedMatch.matchId })
            assertTrue(db.matches().bySeason(2024).size > 1)
            assertEquals(detailBefore, store.detail(originalMatch))
            assertEquals(oldTime, store.sourceTime("detail:${originalMatch.id}"))
            assertEquals(oldTime, store.sourceTime(scheduleUrl))
            assertEquals(oldTime, store.sourceTime("f1:2026"))
            assertEquals(retainedPlayer, db.players().byTeam(league.id, 2024, team).first { it.playerId == retainedPlayer.playerId })
            assertEquals(roster.players.map { it.id }.toSet(), db.players().byTeam(league.id, 2024, team).map { it.playerId }.toSet())
            assertEquals(oldTime, store.sourceTime(rosterUrl))
            assertEquals(preferencesBefore, prefs.all)
            // The retained old bundled baseline must not freeze a later explicit network refresh.
            val refreshed = JsonClient(database = db, archive = archive, transport = { apk.response(rosterUrl)!!.first }).load(rosterUrl) { EspnFootballAdapter.squad(it, 2024) }
            store.writeSquad(league.id, 2024, team, "Network club", refreshed.data, refreshed.fetchedUtc)
            assertEquals(refreshed.fetchedUtc, store.squad(league.id, 2024, team)!!.second)
            assertFalse(refreshed.cached)
            assertFalse(refreshed.archived)
            assertEquals(preferencesBefore, prefs.all)
        } finally { db.close(); context.deleteSharedPreferences(prefsName) }
    }

    @Test fun changedPositionSameIdentityIsConflictAndCompleteNetworkRosterDoesNotResurrectDepartures() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            isolateLegacy(db)
            val apk = SportsArchive(context)
            val store = FootballStore(db)
            val oldTime = "2020-01-01T00:00:00Z"
            store.writeRaces(2020, emptyList(), oldTime)
            store.writeResults(2020, 1, emptyList(), oldTime)
            val url = "https://api.jolpi.ca/ergast/f1/2025/driverstandings.json?limit=100"
            val ranks = apk.read(url) { JolpicaAdapter.standings(it, false, 2025) }!!.data
            val local = ranks.entries.first().copy(position = "99", points = "999")
            store.writeStandings(2025, "driver", ranks.copy(entries = listOf(local)), oldTime)
            val resultUrl = "https://api.jolpi.ca/ergast/f1/2025/1/results.json?limit=100"
            val results = apk.read(resultUrl) { JolpicaAdapter.resultsForRound(it, 2025, 1) }!!.data
            val localResult = results.first().copy(position = "99", points = "999")
            store.writeResults(2025, 1, listOf(localResult), oldTime)
            val tablesUrl = EspnFootballAdapter.standingsUrl("eng.1", 2024)
            val tables = apk.read(tablesUrl) { EspnFootballAdapter.tables(it, 2024) }!!.data
            val localTeam = tables.first().entries.first().copy(position = "99", points = "999")
            store.writeStandings("eng.1", 2024, listOf(tables.first().copy(entries = listOf(localTeam))), oldTime)
            // These partial resources have verified retained bundled provenance, so missing keys
            // may be filled while the changed-position local identities remain conflicting.
            db.localData().putSnapshots(listOf(url, resultUrl, tablesUrl).map {
                row(it, "bundled", apk.response(it)!!.first, oldTime)
            })
            // Legacy resources with valid clocks but no retained original response are complete
            // for upgrade purposes: do not resurrect the other APK members/rounds/positions.
            val unknownCalendar = apk.read("https://api.jolpi.ca/ergast/f1/2021.json?limit=100") { F1Api.parseSeason(it, 2021) }!!.data.take(1)
            store.writeRaces(2021, unknownCalendar, oldTime)
            val unknownTables = apk.read(EspnFootballAdapter.standingsUrl("eng.1", 2025)) { EspnFootballAdapter.tables(it, 2025) }!!.data
            store.writeStandings("eng.1", 2025, listOf(unknownTables.first().copy(entries = unknownTables.first().entries.take(1))), oldTime)
            val unknownRosterUrl = apk.urls().map { it.first }.filter { "/eng.1/teams/" in it && "/roster?season=2024" in it }[1]
            val unknownTeam = unknownRosterUrl.substringAfter("/teams/").substringBefore('/').toInt() + 1_000_000
            val unknownSquad = apk.read(unknownRosterUrl) { EspnFootballAdapter.squad(it, 2024) }!!.data
            store.writeSquad("eng.1", 2024, unknownTeam, "Legacy complete team", unknownSquad.copy(players = unknownSquad.players.take(1)), oldTime)
            val rosterUrl = apk.urls().map { it.first }.first { "/eng.1/teams/" in it && "/roster?season=2024" in it }
            val team = rosterUrl.substringAfter("/teams/").substringBefore('/').toInt() + 1_000_000
            val raw = apk.response(rosterUrl)!!.first
            val body = JSONObject(raw).apply {
                val athletes = getJSONArray("athletes")
                assertTrue(athletes.length() >= 2)
                put("athletes", JSONArray().put(athletes.getJSONObject(0)))
            }.toString()
            val time = Instant.now().toString()
            db.localData().putSnapshots(listOf(row(rosterUrl, "network", body, time)))
            val archive = SportsArchive(context, db)
            DatabaseSeeder(db, store, archive, JsonClient(database = db, archive = archive)).seedIfNeeded()
            assertEquals("0", db.meta().get("seedFailures"))
            assertTrue(db.f1().races(2020).isEmpty())
            assertTrue(db.f1().results(2020, 1).isEmpty())
            assertEquals(unknownCalendar, store.f1Races(2021).first)
            assertEquals(oldTime, store.f1Races(2021).second)
            assertEquals(1, db.standings().byCompetition("eng.1", 2025).size)
            assertEquals(oldTime, store.sourceTime("standings:eng.1:2025"))
            assertEquals(1, db.players().byTeam("eng.1", 2024, unknownTeam).size)
            assertEquals(oldTime, store.squad("eng.1", 2024, unknownTeam)!!.second)
            val rows = db.f1().standings(2025, "driver", ranks.round!!)
            assertEquals(1, rows.count { it.driverId == local.driverId })
            assertEquals("99", rows.single { it.driverId == local.driverId }.position)
            assertEquals("999", rows.single { it.driverId == local.driverId }.points)
            assertEquals(rows.size, rows.map { it.driverId }.toSet().size)
            val resultRows = db.f1().results(2025, 1)
            assertEquals(1, resultRows.count { it.driverId == localResult.driverId })
            assertEquals("99", resultRows.single { it.driverId == localResult.driverId }.position)
            val standingsRows = db.standings().byCompetition("eng.1", 2024)
            assertEquals(1, standingsRows.count { it.teamId == localTeam.team.id })
            assertEquals("99", standingsRows.single { it.teamId == localTeam.team.id }.position)
            assertEquals(1, db.players().byTeam("eng.1", 2024, team).size)
            assertEquals(time, store.squad("eng.1", 2024, team)!!.second)
            // Another APK revision still cannot add the removed members from its stale roster.
            db.meta().put(MetaRow("localDataRevision", "another-apk-revision"))
            DatabaseSeeder(db, store, archive, JsonClient(database = db, archive = archive)).seedIfNeeded()
            assertEquals(1, db.players().byTeam("eng.1", 2024, team).size)
            assertEquals(time, store.squad("eng.1", 2024, team)!!.second)
        } finally { db.close() }
    }
}
