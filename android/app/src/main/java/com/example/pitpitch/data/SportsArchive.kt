package com.example.pitpitch.data

import android.content.Context
import org.json.JSONObject
import com.example.pitpitch.data.db.PitPitchDatabase
import kotlinx.coroutines.CancellationException

/** Packaged source snapshots with original retrieval timestamps. Missing assets are never samples. */
class SportsArchive(internal val context: Context, private val db: PitPitchDatabase? = null) {
    private val assets = context.applicationContext.assets
    private val catalog by lazy { runCatching { JSONObject(assets.open("sports/catalog.json").bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject()) }
    private val roundSeasons = object : LinkedHashMap<Int, Map<String, FootballRoundInfo>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<String, FootballRoundInfo>>?): Boolean = size > 2
    }
    @Synchronized fun footballRounds(year: Int): Map<String, FootballRoundInfo> = roundSeasons.getOrPut(year) {
        if (db != null) return@getOrPut db.localData().rounds(year).associate { r ->
            r.matchId to FootballRoundInfo(r.matchId, r.competitionId, r.season, r.homeTeamId, r.awayTeamId, r.providerNote, r.number, r.source)
        }
        runCatching {
            val rows = org.json.JSONArray(assets.open("rounds/$year.json").bufferedReader().use { it.readText() })
            (0 until rows.length()).map { rows.getJSONObject(it) }.associate { row ->
                row.getString("id") to FootballRoundInfo(row.getString("id"), row.getString("competitionId"),
                    row.getInt("season"), row.getInt("homeTeamId"), row.getInt("awayTeamId"),
                    row.getString("providerNote"), row.getInt("round"), row.getString("source"))
            }
        }.getOrDefault(emptyMap())
    }
    private val standingNotes by lazy {
        val path = "football-standing-notes.json"
        val stored = if (db == null) null else runCatching {
            db?.localData()?.documentBody(path)?.let(::JSONObject)
        }.getOrNull()
        stored ?: runCatching { JSONObject(assets.open(path).bufferedReader().use { it.readText() }) }
            .getOrDefault(JSONObject())
    }
    /** Parsed standings response reused across calls; keyed by URL and invalidated when the stored time changes. */
    private data class ParsedStandings(val fetchedUtc: String, val digest: String, val tables: List<FootballTable>)
    private val standingsCache = java.util.concurrent.ConcurrentHashMap<String, ParsedStandings>()
    private fun parsedStandings(url: String, year: Int): ParsedStandings? {
        val (body, time) = response(url) ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        standingsCache[url]?.takeIf { it.fetchedUtc == time && it.digest == digest }?.let { return it }
        val tables = runCatching { EspnFootballAdapter.tables(body, year) }.getOrNull() ?: return null
        return ParsedStandings(time, digest, tables).also { standingsCache[url] = it }
    }

    fun tableNotes(url: String, year: Int, tables: List<FootballTable>): List<FootballTable> {
        val supplement = standingNotes.optJSONObject(url) ?: return tables
        val parsed = parsedStandings(url, year) ?: return tables
        if (parsed.digest != supplement.optString("archiveSha256")) return tables
        val originalTables = parsed.tables
        val groups = supplement.optJSONObject("groups") ?: return tables
        val notes = groups.keys().asSequence().mapNotNull { groups.optJSONObject(it) }.flatMap { group ->
            group.keys().asSequence().map { it.toInt() + 1_000_000 to group.optString(it) }
        }.toMap()
        return tables.map { table ->
            val old = originalTables.find { it.name == table.name }
            if (old?.entries != table.entries.map { it.copy(providerNote = null) }) table
            else table.copy(entries = table.entries.map { it.copy(providerNote = it.providerNote ?: notes[it.team.id]) })
        }
    }
    /** Raw consumers also reject corrupt stored JSON/time and recover the real immutable APK source. */
    fun response(url: String): Pair<String, String>? = read(url) { body ->
        if (body.trimStart().startsWith("[")) org.json.JSONArray(body) else JSONObject(body)
        body
    }?.let { it.data to it.fetchedUtc }

    internal fun usesDatabase(database: PitPitchDatabase?): Boolean = db != null && db === database
    internal fun packagedDigest(url: String): String? = catalog.optJSONObject(url)?.optString("sha256")?.takeIf { it.isNotBlank() }

    fun <T> read(url: String, parse: (String) -> T): RemoteSnapshot<T>? = readWithOrigin(url, parse)?.first

    /** Origin accompanies the already parsed candidate; no second body read/parse for provenance. */
    internal fun <T> readWithOrigin(url: String, parse: (String) -> T): Pair<RemoteSnapshot<T>, String>? {
        fun parsed(body: String, time: String, origin: String): Pair<RemoteSnapshot<T>, String>? {
            if (parseProviderInstant(time) == null) return null
            return try {
                RemoteSnapshot(parse(body), time, cached = origin == "network", archived = origin != "network") to origin
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        var readFailure: Exception? = null
        if (db != null) {
            val sources = try {
                db.localData().snapshotSources(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                readFailure = error
                emptyList()
            }
            // The persisted bundled row is the installed baseline, not this APK's new
            // catalog. A later explicit network acquisition can still supersede it.
            val ordered = sources.mapNotNull { source ->
                parseProviderInstant(source.fetchedUtc)?.let { source to java.time.Instant.parse(it) }
            }.sortedWith(compareByDescending<Pair<com.example.pitpitch.data.db.SnapshotMetadata, java.time.Instant>> { it.second }
                .thenBy { if (it.first.origin == "bundled") 0 else 1 })
            for ((source, _) in ordered) {
                val row = try {
                    db.localData().snapshot(url, source.origin)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (readFailure == null) readFailure = error
                    null
                }
                row?.let { parsed(it.body, it.fetchedUtc, it.origin) }?.let { return it }
            }
        }
        packagedResponse(url)?.let { (body, time) -> parsed(body, time, "apk") }?.let { return it }
        // An unavailable resource is not a successful database read. A real SQL failure must surface
        // when neither the database nor the immutable APK can supply this resource.
        readFailure?.let { throw it }
        return null
    }
    /** Separate genuine APK input for missing-key bootstrap; never synthesize a merged response. */
    internal fun <T> readPackaged(url: String, parse: (String) -> T): RemoteSnapshot<T>? {
        val (body, time) = packagedResponse(url) ?: return null
        if (parseProviderInstant(time) == null) return null
        return try {
            RemoteSnapshot(parse(body), time, archived = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun packagedResponse(url: String): Pair<String, String>? {
        val entry = catalog.optJSONObject(url) ?: return null
        return try {
            assets.open("sports/${entry.getString("file")}").bufferedReader().use { it.readText() } to entry.getString("fetchedUtc")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    /** Every archived URL with its original retrieval time, for bulk database seeding. */
    fun urls(): List<Pair<String, String>> {
        // Keep corrupt stored resources visible to the seeder's failure/retry accounting,
        // and include newly packaged resources even before their first import.
        val stored = db?.localData()?.snapshotHeaders()?.map { it.url to it.fetchedUtc }.orEmpty()
        val names = catalog.names()
        val packaged = if (names == null) emptyList() else (0 until names.length()).mapNotNull { i ->
            val url = names.getString(i)
            catalog.optJSONObject(url)?.optString("fetchedUtc")?.takeIf { it.isNotBlank() }?.let { url to it }
        }
        return (stored + packaged).distinctBy { it.first }
    }
}
