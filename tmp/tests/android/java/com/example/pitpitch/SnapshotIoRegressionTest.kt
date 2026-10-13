package com.example.pitpitch

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.LocalBackendRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Isolated real Room inputs exercise the production archive/client/importer read paths. */
@RunWith(AndroidJUnit4::class)
class SnapshotIoRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val league get() = footballCompetitions.first { it.id == "eng.1" }
    private fun fixture() = instrumentation.context.assets.open("espn-day-overlay.json").bufferedReader().use { it.readText() }
    private fun facts(body: String) = EspnFootballAdapter.matches(body, league, 2026) to EspnFootballAdapter.timeline(body, league, 2026)
    private fun row(url: String, origin: String, body: String, time: String) =
        SourceSnapshotRow(url, origin, body, time, LocalDataImporter.sha(body.toByteArray(Charsets.UTF_8)))
    private fun revision() = context.assets.open("local-backend-revision.txt").bufferedReader().use { it.readText().trim() }
    private suspend fun markImported(db: PitPitchDatabase) {
        db.meta().put(MetaRow("localDataRevision", revision()))
        db.meta().put(MetaRow("legacyHttpImported", "1"))
        db.meta().put(MetaRow("legacyCalendarImported", "1"))
    }

    @Test fun oversizedUnicodeSnapshotsPreserveExactBodyHashAndProviderFactsThroughConsumers() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val original = fixture()
            val body = JSONObject(original).put("unusedProviderExtension", "赛🏁界".repeat(1_200_000)).toString()
            assertTrue(body.toByteArray(Charsets.UTF_8).size > 8 * 1024 * 1024)
            val url = "https://snapshot-fixture.invalid/large"
            val bundled = row(url, "bundled", body, "2026-10-10T00:00:00Z")
            val network = bundled.copy(origin = "network", fetchedUtc = "2026-10-11T00:00:00Z")
            db.localData().putSnapshots(listOf(bundled, network))
            val archive = SportsArchive(context, db)
            val raw = archive.response(url)!!
            assertEquals(body, raw.first)
            assertEquals(network.fetchedUtc, raw.second)
            assertEquals(bundled.sha256, LocalDataImporter.sha(raw.first.toByteArray(Charsets.UTF_8)))
            val archived = archive.read(url, ::facts)!!
            assertEquals(facts(original), archived.data)
            assertEquals(network.fetchedUtc, archived.fetchedUtc)
            val client = JsonClient(archive = archive, database = db)
            var parses = 0
            val cached = client.archived(url) { parses++; it to facts(it) }!!
            assertEquals("The chosen source body must be parsed only once", 1, parses)
            assertEquals(body, cached.data.first)
            assertEquals(network.sha256, LocalDataImporter.sha(cached.data.first.toByteArray(Charsets.UTF_8)))
            assertEquals(facts(original), cached.data.second)
            assertEquals(network.fetchedUtc, cached.fetchedUtc)
            assertTrue(cached.cached)
            assertEquals(network, db.localData().snapshot(url, "network"))
            db.localData().putSnapshots(listOf(row(url, "network", "", network.fetchedUtc)))
            assertEquals("", db.localData().snapshot(url, "network")!!.body)
        } finally { db.close() }
    }

    @Test fun aSingleOriginSqlFailureDoesNotBlockTheOtherValidOriginAndDoesNotDisappearWithoutFallback() = runBlocking {
        for (failedOrigin in listOf("bundled", "network")) {
            val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
            try {
                val url = "https://snapshot-fixture.invalid/query-failure"
                val body = fixture()
                db.localData().putSnapshots(listOf(row(url, "bundled", body, "2026-10-10T00:00:00Z"),
                    row(url, "network", body, "2026-10-11T00:00:00Z")))
                val sql = db.openHelper.writableDatabase
                sql.execSQL("ALTER TABLE sourceSnapshots RENAME TO sourceSnapshotsBacking")
                // SQLite's built-in integer overflow is evaluated only for this origin's body reads.
                sql.execSQL("CREATE VIEW sourceSnapshots AS SELECT url, origin, CASE WHEN origin = '$failedOrigin' " +
                    "THEN abs(-9223372036854775808) ELSE body END AS body, fetchedUtc, sha256 FROM sourceSnapshotsBacking")
                val client = JsonClient(archive = SportsArchive(context, db), database = db)
                val snapshot = client.archived(url, ::facts)!!
                assertEquals(facts(body), snapshot.data)
                assertEquals(if (failedOrigin == "network") "2026-10-10T00:00:00Z" else "2026-10-11T00:00:00Z", snapshot.fetchedUtc)
                sql.execSQL("DELETE FROM sourceSnapshotsBacking WHERE origin != ?", arrayOf(failedOrigin))
                try {
                    client.archived(url, ::facts)
                    fail("A real SQL failure with no valid source must propagate")
                } catch (_: SQLiteException) { }
            } finally { db.close() }
        }
    }

    @Test fun importerRepairsInvalidOldTimesAndBodiesUsingActualCatalogAndLegacyTimesThenStaysIdempotent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        val prefsName = "snapshot-import-recovery-fixture"
        val prefs = context.getSharedPreferences(prefsName, 0)
        prefs.edit().clear().commit()
        try {
            val apk = SportsArchive(context)
            val urls = (2020..2023).map { "https://api.jolpi.ca/ergast/f1/$it.json?limit=100" }
            val originals = urls.map { apk.response(it)!! }
            db.localData().putSnapshots(listOf(
                row(urls[0], "bundled", originals[0].first, "invalid old time"),
                row(urls[1], "bundled", "broken JSON", "2099-01-01T00:00:00Z"),
                row(urls[2], "network", originals[2].first, "invalid old time"),
                row(urls[3], "network", "broken JSON", "2099-01-01T00:00:00Z")))
            val actualLegacyTimes = originals.map { Instant.parse(it.second).plusSeconds(60).toString() }
            for (index in 2..3) prefs.edit().putString("response:${urls[index]}", originals[index].first)
                .putString("time:${urls[index]}", actualLegacyTimes[index]).commit()
            prefs.edit().putString("response:${urls[0]}", originals[0].first)
                .putString("time:${urls[0]}", "invalid incoming acquisition time").commit()
            val importer = LocalDataImporter(context, db, prefs, RaceCache(prefs))
            importer.importIfNeeded()
            assertNull(db.localData().snapshot(urls[0], "network"))
            for (index in urls.indices) {
                val origin = if (index < 2) "bundled" else "network"
                val expectedTime = if (index < 2) originals[index].second else actualLegacyTimes[index]
                val restored = db.localData().snapshot(urls[index], origin)!!
                assertEquals(originals[index].first, restored.body)
                assertEquals(expectedTime, restored.fetchedUtc)
                assertEquals(LocalDataImporter.sha(restored.body.toByteArray(Charsets.UTF_8)), restored.sha256)
                assertEquals(F1Api.parseSeason(originals[index].first, 2020 + index),
                    F1Api.parseSeason(restored.body, 2020 + index))
            }
            val before = urls.mapIndexed { index, url -> db.localData().snapshot(url, if (index < 2) "bundled" else "network") }
            prefs.edit().putString("response:${urls[2]}", "later invalid mutation").commit()
            importer.importIfNeeded()
            assertEquals(before, urls.mapIndexed { index, url -> db.localData().snapshot(url, if (index < 2) "bundled" else "network") })
            assertEquals("later invalid mutation", prefs.getString("response:${urls[2]}", null))
            assertEquals(revision(), db.meta().get("localDataRevision"))
        } finally { db.close(); context.deleteSharedPreferences(prefsName) }
    }

    @Test fun sameRevisionCorruptBundledResourceStillReadsRealApkAndNeverInventsAcquisitionTime() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            markImported(db)
            val url = "https://api.jolpi.ca/ergast/f1/2020.json?limit=100"
            val original = SportsArchive(context).response(url)!!
            val archive = SportsArchive(context, db)
            for ((body, time) in listOf(original.first to "invalid time", "broken JSON" to "2099-01-01T00:00:00Z")) {
                db.localData().putSnapshots(listOf(row(url, "bundled", body, time)))
                LocalDataImporter(context, db).importIfNeeded()
                assertEquals(time, db.localData().snapshotMetadata(url, "bundled")!!.fetchedUtc)
                assertEquals(original, archive.response(url))
                val restored = archive.read(url) { F1Api.parseSeason(it, 2020) }!!
                assertEquals(F1Api.parseSeason(original.first, 2020), restored.data)
                assertEquals(original.second, restored.fetchedUtc)
                assertEquals(restored, JsonClient(archive = archive, database = db).archived(url) { F1Api.parseSeason(it, 2020) })
            }
            try {
                archive.read(url) { throw CancellationException("cancel consumer parsing") }
                fail("Cancellation must not fall back to another source")
            } catch (_: CancellationException) { }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("ALTER TABLE sourceSnapshots RENAME TO sourceSnapshotsBacking")
            sql.execSQL("CREATE VIEW sourceSnapshots AS SELECT url, origin, abs(-9223372036854775808) AS body, " +
                "fetchedUtc, sha256 FROM sourceSnapshotsBacking")
            assertEquals(original, archive.response(url))
            val restored = archive.read(url) { F1Api.parseSeason(it, 2020) }!!
            assertEquals(original.second, restored.fetchedUtc)
            assertEquals(F1Api.parseSeason(original.first, 2020), restored.data)
        } finally { db.close() }
    }

    @Test fun productionReferencePreloadReadsLargeUnicodeDocumentsAndRecoversCorruptImmutableDocuments() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            markImported(db)
            val path = "football-chinese-names.json"
            val original = context.assets.open(path).bufferedReader().use { JSONObject(it.readText()) }
            val extension = "赛🏁界".repeat(1_200_000)
            val large = JSONObject(original.toString()).put("unusedProviderExtension", extension).toString()
            db.localData().putDocuments(listOf(LocalDocumentRow(path, large, LocalDataImporter.sha(large.toByteArray(Charsets.UTF_8))),
                LocalDocumentRow("football-team-colors.json", "broken JSON", "corrupt")))
            val backend = LocalBackendRepository(context, db)
            backend.prepare()
            val references = backend.references.value
            assertTrue(references.ready)
            assertEquals(extension, references.names.getString("unusedProviderExtension"))
            references.names.remove("unusedProviderExtension")
            assertEquals(original.toString(), references.names.toString())
            val expectedColors = context.assets.open("football-team-colors.json").bufferedReader().use { JSONObject(it.readText()) }
            assertEquals(expectedColors.toString(), references.teamColors.toString())
        } finally { db.close() }
    }

    @Test fun globalSnapshotEnumerationFailureIsNotReportedAsAnEmptySuccessfulArchive() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            db.openHelper.writableDatabase.execSQL("DROP TABLE sourceSnapshots")
            try {
                SportsArchive(context, db).urls()
                fail("A missing snapshot table must not become a successful empty archive")
            } catch (_: SQLiteException) { }
        } finally { db.close() }
    }
}
