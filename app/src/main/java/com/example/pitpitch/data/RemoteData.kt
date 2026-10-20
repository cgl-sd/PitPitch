package com.example.pitpitch.data

import android.content.SharedPreferences
import com.example.pitpitch.data.repository.SportsHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Cache keys are complete URLs: seasons, rounds and resource types cannot mix. */
data class RemoteSnapshot<T>(val data: T, val fetchedUtc: String, val cached: Boolean = false, val error: String? = null, val archived: Boolean = false)
class JsonClient(private val preferences: SharedPreferences? = null, private val timeoutMs: Int = 12000, private val transport: ((String) -> String)? = null, private val archive: SportsArchive? = null,
    private val database: com.example.pitpitch.data.db.PitPitchDatabase? = null, private val totalTimeoutMs: Int = 20_000) {
    suspend fun <T> load(url: String, parse: (String) -> T): RemoteSnapshot<T> = loadScoped(url, null, parse)
    suspend fun <T> loadScoped(url: String, scopeMatchIds: Set<String>?, parse: (String) -> T): RemoteSnapshot<T> = withContext(Dispatchers.IO) {
        val key = "response:$url"
        try {
            coroutineContext.ensureActive()
            val body = transport?.invoke(url) ?: SportsHttp.request(url, timeoutMs, totalTimeoutMs)
            coroutineContext.ensureActive()
            val value = parse(body) // Invalid responses never replace valid cached data.
            coroutineContext.ensureActive()
            val now = Instant.now().toString()
            if (database != null) database.transaction {
                val previous = database.localData().snapshot(url, "network")
                val validPrevious = previous?.let {
                    try { parse(it.body); Instant.parse(it.fetchedUtc) } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
                if (validPrevious == null || !Instant.parse(now).isBefore(validPrevious)) {
                    val row = com.example.pitpitch.data.db.SourceSnapshotRow(
                        url, "network", body, now, com.example.pitpitch.data.db.LocalDataImporter.sha(body.toByteArray()))
                    coroutineContext.ensureActive()
                    database.localData().putSnapshots(listOf(row))
                    // Retain the original response; scope its later re-import to the requested round.
                    database.meta().put(com.example.pitpitch.data.db.MetaRow("snapshotScope:$url",
                        JSONObject().put("fetchedUtc", now).put("ids", scopeMatchIds?.let { JSONArray(it.toList()) }).toString()))
                }
            } else {
                coroutineContext.ensureActive()
                preferences?.edit()?.putString(key, body)?.putString("time:$url", now)?.apply()
            }
            RemoteSnapshot(value, now)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            archived(url, parse)?.let {
                return@withContext it.copy(error = com.example.pitpitch.data.repository.readableError(error))
            }
            throw error
        }
    }
    fun <T> archived(url: String, parse: (String) -> T): RemoteSnapshot<T>? {
        // The database-backed archive already validates and selects both stored origins.
        // Re-reading network here would parse the same candidate twice and could mix versions.
        if (archive != null && archive.usesDatabase(database)) return archive.read(url, parse)
        var readFailure: Exception? = null
        val bundled = try { archive?.read(url, parse) } catch (error: Exception) {
            if (error is CancellationException) throw error
            readFailure = error
            null
        }
        val cached = if (database != null) {
            val row = try { database.localData().snapshot(url, "network") } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (readFailure == null) readFailure = error
                null
            }
            row?.let {
                try { RemoteSnapshot(parse(it.body), it.fetchedUtc, cached = true) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
            }
        } else {
            val body = preferences?.getString("response:$url", null)
            val time = preferences?.getString("time:$url", null)
            if (body != null && time != null) {
                try { RemoteSnapshot(parse(body), time, cached = true) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
            } else null
        }
        val preferred = preferredLocalSnapshot(bundled, cached)
        if (preferred == null) readFailure?.let { throw it }
        return preferred
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

data class RankingEntry(val position: String, val name: String, val team: String, val points: String, val detail: String, val grid: String? = null, val nationality: String? = null, val driverId: String? = null, val constructorId: String? = null)
data class F1Rankings(val round: String?, val entries: List<RankingEntry>)
object JolpicaAdapter {
    fun standings(body: String, constructors: Boolean, expectedSeason: Int, expectedRound: Int? = null): F1Rankings {
        val root = JSONObject(body).getJSONObject("MRData")
        val table = root.getJSONObject("StandingsTable")
        require(table.getString("season").toInt() == expectedSeason) { "Provider returned a different standings season" }
        expectedRound?.let { require(table.getString("round").toInt() == it) }
        val lists = table.getJSONArray("StandingsLists").objects()
        require(lists.size <= 1) { "Provider returned multiple standings" }
        val total = root.getString("total").toInt()
        require(total >= 0)
        if (lists.isEmpty()) {
            require(total == 0)
            return F1Rankings(null, emptyList())
        }
        val list = lists.single()
        require(list.getString("season").toInt() == expectedSeason)
        val round = list.getString("round").toInt()
        require(round > 0)
        table.text("round")?.let { require(it.toInt() == round) }
        expectedRound?.let { require(round == it) }
        val key = if (constructors) "ConstructorStandings" else "DriverStandings"
        val rows = list.getJSONArray(key).objects()
        require(total == rows.size) { "Provider returned incomplete standings" }
        val positions = rows.map { it.getString("position").toInt().also { position -> require(position > 0) } }
        require(positions.distinct().size == positions.size)
        val identities = rows.map { row ->
            row.getJSONObject(if (constructors) "Constructor" else "Driver")
                .getString(if (constructors) "constructorId" else "driverId").also { require(it.isNotBlank()) }
        }
        require(identities.distinct().size == identities.size)
        return F1Rankings(round.toString(), rows.map { row ->
            val driver = if (constructors) null else row.getJSONObject("Driver")
            val teams = if (constructors) emptyList() else row.getJSONArray("Constructors").objects().also { entries ->
                require(entries.isNotEmpty())
                val ids = entries.map { it.getString("constructorId").also { id -> require(id.isNotBlank()) } }
                require(ids.distinct().size == ids.size)
            }
            RankingEntry(row.text("positionText") ?: row.getString("position"),
                if (constructors) row.getJSONObject("Constructor").getString("name") else "${driver!!.getString("givenName")} ${driver.getString("familyName")}",
                if (constructors) "车队" else teams.joinToString(" / ") { it.getString("name") },
                row.text("points") ?: "—", row.text("wins")?.let { "获胜 $it 场" } ?: "获胜次数未提供", nationality = driver?.text("nationality"),
                driverId = driver?.text("driverId"), constructorId = if (constructors) row.getJSONObject("Constructor").text("constructorId")
                    else teams.singleOrNull()?.text("constructorId"))
        }.also { entries ->
            require(entries.map { it.position }.distinct().size == entries.size) { "Provider returned duplicate standings positions" }
        })
    }
    fun resultsForRound(body: String, season: Int, round: Int): List<RankingEntry> {
        val root = JSONObject(body).getJSONObject("MRData")
        val table = root.getJSONObject("RaceTable")
        require(table.optString("season", season.toString()) == season.toString())
        require(table.optString("round", round.toString()) == round.toString())
        val races = table.getJSONArray("Races").objects()
        require(races.size <= 1)
        if (races.isEmpty()) { require(root.optString("total", "0") == "0"); return emptyList() }
        val race = races.single()
        require(race.getString("season").toInt() == season && race.getString("round").toInt() == round)
        root.text("total")?.toIntOrNull()?.let { require(it == race.getJSONArray("Results").length()) }
        return results(body)
    }
    fun results(body: String): List<RankingEntry> {
        val races = JSONObject(body).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
        return races.objects().firstOrNull()?.getJSONArray("Results")?.objects().orEmpty().map { row ->
            val driver = row.getJSONObject("Driver")
            RankingEntry(row.text("position") ?: "—", "${driver.getString("givenName")} ${driver.getString("familyName")}", row.getJSONObject("Constructor").getString("name"), row.text("points") ?: "—",
                "${row.text("status") ?: "状态未提供"} · ${row.text("laps") ?: "—"} 圈${row.optJSONObject("Time")?.text("time")?.let { " · $it" }.orEmpty()}", grid = row.text("grid"), nationality = driver.text("nationality"),
                driverId = driver.text("driverId"), constructorId = row.getJSONObject("Constructor").text("constructorId"))
        }.sortedBy { it.position.toIntOrNull() ?: Int.MAX_VALUE }.also { entries ->
            require(entries.all { (it.position.toIntOrNull() ?: 0) > 0 })
            require(entries.map { it.position }.distinct().size == entries.size)
            require(entries.map { it.driverId ?: it.name }.distinct().size == entries.size)
        }
    }
}

/** External crests use HTTPS only; missing or unsafe links remain unavailable. */
fun secureBadgeUrl(value: String?): String? = value?.trim()?.takeIf {
    runCatching { val uri = java.net.URI(it); uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false)
}
