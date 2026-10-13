package com.example.pitpitch.data.db

import android.content.Context
import com.example.pitpitch.data.f1ArchiveYears
import com.example.pitpitch.data.footballArchiveYears
import com.example.pitpitch.data.parseProviderInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.time.Instant

/** APK inputs are bootstrap material, never a second runtime database. */
class LocalDataImporter(private val context: Context, private val db: PitPitchDatabase,
    private val legacyHttp: android.content.SharedPreferences = context.getSharedPreferences("pit_pitch_api_cache", Context.MODE_PRIVATE),
    private val legacyCalendar: com.example.pitpitch.data.RaceCache = com.example.pitpitch.data.RaceCache(context.getSharedPreferences("pit_pitch_user", Context.MODE_PRIVATE))) {
    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        val revision = context.assets.open("local-backend-revision.txt").bufferedReader().use { it.readText().trim() }
        if (db.meta().get("localDataRevision") == revision) {
            importLegacyCaches()
            return@withContext
        }
        val manifest = JSONObject(context.assets.open("local-backend-manifest.json").bufferedReader().use { it.readText() })
        check(manifest.getString("revision") == revision)
        val files = manifest.getJSONObject("files")
        db.transaction {
            if (db.meta().get("localDataRevision") == revision) return@transaction
            fun bytes(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }.also {
                check(sha(it) == files.getString(path)) { "Invalid packaged input: $path" }
            }
            fun json(path: String) = JSONObject(bytes(path).toString(Charsets.UTF_8))
            val dao = db.localData()
            // No image BLOBs. Paths and original provenance are indexed, binaries stay in files.
            val media = mutableListOf<LocalAssetRow>()
            for ((path, kind) in listOf("f1-media/catalog.json" to "f1", "team-badges/catalog.json" to "badge")) {
                val catalog = json(path)
                for (key in catalog.keys()) {
                    val r = catalog.getJSONObject(key)
                    val asset = path.substringBeforeLast('/') + "/" + r.getString("file")
                    check(r.getString("sha256") == files.getString(asset))
                    media += LocalAssetRow(if (kind == "f1") key else "badge/$key", if (kind == "f1") r.getString("role") else "badge",
                        if (kind == "f1") r.getString("entity") else key, r.optInt("season").takeIf { it >= 2020 },
                        asset, if (kind == "f1") r.getString("url") else key, r.getString("sha256"),
                        r.optInt("width").takeIf { it > 0 }, r.optInt("height").takeIf { it > 0 }, r.toString())
                }
            }
            val badgeSources = json("badges/manifest.json").getJSONObject("sources")
            files.keys().asSequence().filter { it.startsWith("badges/") && it.endsWith(".png") }.forEach { path ->
                val id = path.substringAfterLast('/').substringBefore('.')
                media += LocalAssetRow("legacyBadge/$id", "legacyBadge", id, null, path, badgeSources.optString(id),
                    files.getString(path), null, null, "{}")
            }
            dao.clearAssets(); media.chunked(100).forEach { dao.putAssets(it) }
            dao.clearDocuments(); dao.clearRounds()
            var sources: JSONObject? = null
            files.keys().asSequence().filter { path ->
                (path.endsWith(".json") || path.endsWith(".geojson") || path.endsWith(".txt")) &&
                    (!path.startsWith("sports/") || path == "sports/catalog.json")
            }.forEach { path ->
                val body = bytes(path).toString(Charsets.UTF_8)
                dao.putDocuments(listOf(LocalDocumentRow(path, body, files.getString(path))))
                if (path == "sports/catalog.json") sources = JSONObject(body)
                val year = path.removePrefix("rounds/").removeSuffix(".json").toIntOrNull()
                if (path.startsWith("rounds/") && year in footballArchiveYears()) {
                    val rows = JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map { r ->
                        FootballRoundRow(r.getInt("season"), r.getString("id"), r.getString("competitionId"), r.getInt("homeTeamId"),
                            r.getInt("awayTeamId"), r.getString("providerNote"), r.getInt("round"), r.getString("source"))
                    }
                    rows.chunked(200).forEach { dao.putRounds(it) }
                }
            }
            val catalog = checkNotNull(sources)
            val batch = mutableListOf<SourceSnapshotRow>()
            var batchBytes = 0
            for (url in catalog.keys()) {
                val r = catalog.getJSONObject(url)
                val path = "sports/" + r.getString("file")
                val time = r.getString("fetchedUtc")
                Instant.parse(time)
                check(files.getString(path) == r.getString("sha256"))
                val raw = bytes(path)
                if (batch.isNotEmpty() && raw.size > SNAPSHOT_BATCH_BYTES - batchBytes) {
                    dao.putSnapshots(batch)
                    batch.clear()
                    batchBytes = 0
                }
                val old = dao.snapshotMetadata(url, "bundled")
                // An APK upgrade is bootstrap, not an explicit provider refresh. Keep valid
                // device material even when this package advertises a later acquisition time.
                val keepOld = old?.fetchedUtc?.let(::parseProviderInstant) != null &&
                    dao.snapshot(url, "bundled")?.body?.let { validResource(url, it) } == true
                if (!keepOld) {
                    val body = raw.toString(Charsets.UTF_8)
                    check(validResource(url, body)) { "Invalid packaged resource: $url" }
                    batch += SourceSnapshotRow(url, "bundled", body, time, r.getString("sha256"))
                    batchBytes += raw.size
                }
                // An oversized immutable resource is written alone, never retained alongside another body.
                if (batch.size == 20 || batchBytes >= SNAPSHOT_BATCH_BYTES) {
                    dao.putSnapshots(batch)
                    batch.clear()
                    batchBytes = 0
                }
            }
            if (batch.isNotEmpty()) dao.putSnapshots(batch)
            importLegacyCaches()
            db.meta().put(MetaRow("localDataRevision", revision))
        }
    }
    private suspend fun importLegacyCaches() {
        if (db.meta().get("legacyHttpImported") == "1" && db.meta().get("legacyCalendarImported") == "1") return
        db.transaction {
            val dao = db.localData()
            if (db.meta().get("legacyHttpImported") != "1") {
                val legacy = legacyHttp
                for ((key, value) in legacy.all) {
                    if (!key.startsWith("response:") || value !is String) continue
                    val url = key.removePrefix("response:")
                    val time = legacy.getString("time:$url", null) ?: continue
                    val incomingTime = parseProviderInstant(time)?.let(Instant::parse) ?: continue
                    if (!validJson(value)) continue
                    val old = dao.snapshotMetadata(url, "network")
                    val oldTime = old?.fetchedUtc?.let(::parseProviderInstant)?.let(Instant::parse)
                    val keepOld = oldTime != null && !incomingTime.isAfter(oldTime) &&
                        dao.snapshot(url, "network")?.body?.let(::validJson) == true
                    if (!keepOld) {
                        dao.putSnapshots(listOf(SourceSnapshotRow(url, "network", value, time, sha(value.toByteArray(Charsets.UTF_8)))))
                    }
                }
                db.meta().put(MetaRow("legacyHttpImported", "1"))
            }
            if (db.meta().get("legacyCalendarImported") != "1") {
                val cache = legacyCalendar
                val store = FootballStore(db)
                for (year in f1ArchiveYears()) {
                    val snapshot = cache.snapshot(year) ?: continue
                    if (parseProviderInstant(snapshot.fetchedUtc) != null) store.writeRaces(year, snapshot.data, snapshot.fetchedUtc)
                }
                db.meta().put(MetaRow("legacyCalendarImported", "1"))
            }
        }
    }
    companion object {
        private const val SNAPSHOT_BATCH_BYTES = 2 * 1024 * 1024
        fun sha(bytes: ByteArray): String = buildString(64) {
            val hex = "0123456789abcdef"
            MessageDigest.getInstance("SHA-256").digest(bytes).forEach { b ->
                val n = b.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15])
            }
        }
        private fun validJson(body: String) = runCatching { if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body); true }.getOrDefault(false)
        /** Use the same provider validation as runtime reads for the resources seeded into facts. */
        private fun validResource(url: String, body: String): Boolean = runCatching {
            when {
                "/scoreboard?dates=" in url -> {
                    val league = com.example.pitpitch.data.footballCompetitions.first { it.id == url.substringAfter("soccer/").substringBefore('/') }
                    val year = url.substringAfter("dates=").substringBefore('&').toInt()
                    com.example.pitpitch.data.EspnFootballAdapter.scoreboard(body, league, year)
                    com.example.pitpitch.data.EspnFootballAdapter.scoreboard(body, league, year - 1)
                }
                "/standings?season=" in url -> com.example.pitpitch.data.EspnFootballAdapter.tables(body, url.substringAfter("season=").toInt())
                "/roster?season=" in url -> com.example.pitpitch.data.EspnFootballAdapter.squad(body, url.substringAfter("season=").toInt())
                "/ergast/f1/" in url -> {
                    val rest = url.substringAfter("/ergast/f1/")
                    val season = rest.substringBefore('/').substringBefore('.').toInt()
                    when {
                        "driverstandings" in rest || "constructorstandings" in rest ->
                            com.example.pitpitch.data.JolpicaAdapter.standings(body, "constructorstandings" in rest, season)
                        "/results.json" in rest && "offset" !in rest ->
                            com.example.pitpitch.data.JolpicaAdapter.resultsForRound(body, season, rest.substringAfter('/').substringBefore('/').toInt())
                        rest.startsWith("$season.json") -> com.example.pitpitch.data.F1Api.parseSeason(body, season)
                        else -> check(validJson(body))
                    }
                }
                else -> check(validJson(body))
            }
            true
        }.getOrDefault(false)
    }
}
