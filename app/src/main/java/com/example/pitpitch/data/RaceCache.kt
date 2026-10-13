package com.example.pitpitch.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, local-only snapshot of the public race calendars. Keeping season keys separate
 * makes favorites from a previously viewed season available offline.
 * This does not cache or imply live timing / race results.
 */
class RaceCache(private val preferences: SharedPreferences) {
    private val seasonsKey = "cached_f1_seasons"

    private fun parsedSeason(year: Int): List<F1Race>? = runCatching {
        val serialized = preferences.getString("f1_calendar_$year", null) ?: return@runCatching null
        val items = JSONArray(serialized)
        (0 until items.length()).map { i ->
            val json = items.getJSONObject(i)
            val sessions = json.optJSONArray("sessions") ?: JSONArray()
            require(json.getInt("season") == year) { "Cached calendar belongs to another season" }
            F1Race(
                season = json.getInt("season"),
                round = json.getInt("round"),
                name = json.getString("name"),
                circuit = json.getString("circuit"),
                date = json.getString("date"),
                utcTime = json.optString("utcTime").takeIf(String::isNotBlank),
                country = json.optString("country"), circuitId = json.text("circuitId"),
                sessions = (0 until sessions.length()).map { index ->
                    val session = sessions.getJSONObject(index)
                    F1Session(
                        title = session.getString("title"),
                        date = session.getString("date"),
                        time = session.optString("time").takeIf(String::isNotBlank),
                    )
                },
            )
        }
    }.getOrNull()

    fun readSeason(year: Int): List<F1Race> = parsedSeason(year).orEmpty()

    fun snapshot(year: Int): RemoteSnapshot<List<F1Race>>? {
        val races = parsedSeason(year) ?: return null
        val time = updated(year) ?: return null
        return RemoteSnapshot(races, time, cached = true)
    }

    fun updated(year: Int): String? = preferences.getString("f1_updated_$year", null)

    fun allCachedRaces(): List<F1Race> = cachedSeasons().filter { it in f1ArchiveYears() }.flatMap(::readSeason)

    fun saveSeason(year: Int, races: List<F1Race>, fetchedUtc: String? = null) {
        // A successful empty season is valid; failures never reach this method.
        val values = JSONArray()
        races.forEach { race ->
            val sessions = JSONArray()
            race.sessions.forEach { session ->
                sessions.put(JSONObject().apply {
                    put("title", session.title)
                    put("date", session.date)
                    put("time", session.time ?: "")
                })
            }
            values.put(JSONObject().apply {
                put("season", race.season)
                put("round", race.round)
                put("name", race.name)
                put("circuit", race.circuit)
                put("date", race.date)
                put("utcTime", race.utcTime ?: "")
                put("country", race.country)
                put("circuitId", race.circuitId ?: "")
                put("sessions", sessions)
            })
        }
        val knownSeasons = (cachedSeasons() + year).map(Int::toString).toSet()
        preferences.edit()
            .putString("f1_calendar_$year", values.toString())
            .putStringSet(seasonsKey, knownSeasons)
            .putString("f1_updated_$year", fetchedUtc ?: java.time.Instant.now().toString())
            .apply()
    }

    private fun cachedSeasons(): Set<Int> = preferences.getStringSet(seasonsKey, emptySet())
        .orEmpty().mapNotNull(String::toIntOrNull).toSet()
}
