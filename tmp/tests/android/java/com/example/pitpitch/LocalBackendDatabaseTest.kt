package com.example.pitpitch

import androidx.room.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.localBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException

@Database(entities = [MatchRow::class, StandingRow::class, PlayerRow::class, MatchTeamRow::class, LineupRow::class,
    StatRow::class, EventRow::class, F1RaceRow::class, F1SessionRow::class, F1ResultRow::class,
    F1StandingRow::class, SourceRow::class, MetaRow::class], version = 2, exportSchema = false)
abstract class LegacyBackendV2 : RoomDatabase() {
    abstract fun f1(): F1Dao
    abstract fun meta(): MetaDao
    abstract fun sources(): SourceLogDao
}

class LocalBackendDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyCachesMigrateOnceWithoutDiscardingOriginalPreferences() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        val prefs = context.getSharedPreferences("backend-cache-fixture", 0)
        prefs.edit().clear().commit()
        try {
            val url = "https://api.jolpi.ca/ergast/f1/2020.json?limit=100"
            val original = SportsArchive(context).response(url)!!
            val newer = java.time.Instant.parse(original.second).plusSeconds(60).toString()
            prefs.edit().putString("response:$url", original.first).putString("time:$url", newer).commit()
            val cache = RaceCache(prefs); cache.saveSeason(2020, emptyList(), newer)
            val importer = LocalDataImporter(context, db, prefs, cache)
            importer.importIfNeeded()
            assertEquals(newer, db.localData().snapshot(url, "network")!!.fetchedUtc)
            assertEquals(original.first, prefs.getString("response:$url", null))
            assertEquals(newer, FootballStore(db).f1Races(2020).second)
            assertTrue(FootballStore(db).f1Races(2020).first.isEmpty())
            prefs.edit().putString("response:$url", "later test mutation").commit()
            importer.importIfNeeded()
            assertEquals(original.first, db.localData().snapshot(url, "network")!!.body)
            assertEquals("1", db.meta().get("legacyCalendarImported"))
        } finally { db.close(); context.deleteSharedPreferences("backend-cache-fixture") }
    }
    @Test fun versionTwoMigrationPreservesExistingDataAndAddsIndexedTables() = runBlocking {
        val name = "backend-migration-fixture.db"; context.deleteDatabase(name)
        val legacy = Room.databaseBuilder(context, LegacyBackendV2::class.java, name).build()
        val row = F1StandingRow(2020, "constructor", "17", "1", "Mercedes", "车队", "573", "获胜 13 场",
            "2026-10-10T00:00:00Z", constructorId = "mercedes")
        legacy.f1().upsertStandings(listOf(row)); legacy.meta().put(MetaRow("user-fixture", "preserve")); legacy.close()
        val db = Room.databaseBuilder(context, PitPitchDatabase::class.java, name)
            .addMigrations(PitPitchDatabase.MIGRATION_2_3, PitPitchDatabase.MIGRATION_3_4).build()
        try {
            assertEquals(row, db.f1().standings(2020, "constructor", "17").single())
            assertEquals("preserve", db.meta().get("user-fixture"))
            db.localData().putAssets(listOf(LocalAssetRow("car/2020/mercedes", "car", "mercedes", 2020,
                "f1-media/example.webp", "https://example.invalid/asset", "hash", 100, 40, "{}")))
            assertNotNull(db.localData().asset("car/2020/mercedes"))
            assertEquals(4, db.openHelper.readableDatabase.version)
            val plan = db.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT * FROM players WHERE competitionId = 'eng.1' AND season = 2024 AND teamId = 1000360"
            ).use { cursor ->
                buildString { while (cursor.moveToNext()) append(cursor.getString(3)) }
            }
            assertTrue(plan, plan.contains("index_players_competitionId_season_teamId"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun packagedInputsArePersistedAndIndexedReadsStaySmall() = runBlocking {
        localBackend(context).prepare()
        val db = PitPitchDatabase.build(context); val dao = db.localData()
        val catalog = context.assets.open("sports/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(catalog.length(), dao.snapshotCount("bundled"))
        assertTrue(dao.assetCount() >= 700)
        val archive = SportsArchive(context, db)
        val packaged = SportsArchive(context)
        for (year in 2020..2026) {
            for (kind in listOf("driverstandings", "constructorstandings")) {
                val url = "https://api.jolpi.ca/ergast/f1/$year/$kind.json?limit=100"
                val source = catalog.getJSONObject(url)
                val row = dao.snapshot(url, "bundled")!!
                assertEquals(source.getString("sha256"), LocalDataImporter.sha(row.body.toByteArray()))
                assertEquals(source.getString("fetchedUtc"), row.fetchedUtc)
            }
            assertEquals(packaged.footballRounds(year), archive.footballRounds(year))
        }
        assertNotNull(dao.documentBody("circuit-maps/LICENSE.txt"))
        assertEquals("曼联", FootballNames.get(context).team(1000360, "Manchester United"))
        val times = (1..200).map {
            val start = System.nanoTime(); assertNotNull(dao.asset("car/2020/mercedes")); (System.nanoTime()-start)/1_000_000.0
        }.sorted()
        val p95 = times[189]
        val plan = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM localAssets WHERE resourceKey = 'car/2020/mercedes'").use { cursor ->
            buildString { while (cursor.moveToNext()) append(cursor.getString(3)) }
        }
        assertTrue(plan, plan.contains("SEARCH") && !plan.contains("SCAN"))
        assertTrue("Indexed metadata p95 must stay below 100ms, was $p95", p95 < 100)
        // Small preloaded references are safe on the actual UI thread (Room forbids SQL there).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            repeat(1000) {
                assertEquals("曼联", FootballNames.get(context).team(1000360, "Manchester United"))
                assertNotNull(F1Media.asset(context, "car", "mercedes", 2020))
            }
        }
        val report = JSONObject().put("metadataP50Ms", times[99]).put("metadataP95Ms", p95).put("metadataMaxMs", times.last())
            .put("queryPlan", plan).put("snapshotCount", dao.snapshotCount("bundled")).put("assetCount", dao.assetCount())
        val file = File(context.cacheDir, "pitpitch-validation/local-backend-performance.json"); file.parentFile!!.mkdirs(); file.writeText(report.toString(2))
        println(report)
    }
    @Test fun databaseResponseCacheKeepsFreshnessAndRejectsBrokenFallbacks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val url = "https://backend-fixture.invalid/data"
            val bundled = "{\"value\":1}"; val network = "{\"value\":2}"
            db.localData().putSnapshots(listOf(SourceSnapshotRow(url, "bundled", bundled, "2026-10-10T00:00:00Z", LocalDataImporter.sha(bundled.toByteArray()))))
            val archive = SportsArchive(context, db)
            val client = JsonClient(transport = { network }, archive = archive, database = db)
            val downloaded = client.load(url) { JSONObject(it).getInt("value") }
            assertEquals(2, downloaded.data)
            val offline = JsonClient(transport = { throw SocketTimeoutException() }, archive = archive, database = db)
            val fallback = offline.load(url) { JSONObject(it).getInt("value") }
            assertEquals(2, fallback.data); assertEquals(downloaded.fetchedUtc, fallback.fetchedUtc); assertNotNull(fallback.error)
            db.localData().putSnapshots(listOf(SourceSnapshotRow(url, "network", "broken", downloaded.fetchedUtc, "invalid")))
            assertEquals(1, offline.load(url) { JSONObject(it).getInt("value") }.data)
            db.localData().putSnapshots(listOf(SourceSnapshotRow(url, "network", network, "2026-10-09T00:00:00Z", "hash")))
            assertEquals(1, offline.load(url) { JSONObject(it).getInt("value") }.data)
        } finally { db.close() }
    }
}
