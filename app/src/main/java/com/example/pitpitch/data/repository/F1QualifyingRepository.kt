package com.example.pitpitch.data.repository

import com.example.pitpitch.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class QualifyingEntry(val position: String?, val driverId: String, val name: String,
    val constructorId: String?, val team: String, val q1: String?, val q2: String?, val q3: String?)

/** Full per-round responses are persisted in Room's URL-keyed sourceSnapshots, parsed once on IO. */
class F1QualifyingRepository(private val client: JsonClient) {
    suspend fun load(race: F1Race, force: Boolean): RemoteSnapshot<List<QualifyingEntry>> = withContext(Dispatchers.IO) {
        val url = "https://api.jolpi.ca/ergast/f1/${race.season}/${race.round}/qualifying.json?limit=100"
        val parse: (String) -> List<QualifyingEntry> = { parse(it, race) }
        if (!force) client.archived(url, parse)?.let { return@withContext it }
        val response = client.load(url, parse)
        val chosen = preferredLocalSnapshot(response, client.archived(url, parse)) ?: response
        chosen.copy(error = response.error ?: chosen.error)
    }
    companion object {
        fun parse(body: String, race: F1Race): List<QualifyingEntry> {
            val root = JSONObject(body).getJSONObject("MRData")
            val races = root.getJSONObject("RaceTable").getJSONArray("Races").objects()
            require(races.size <= 1) { "排位响应包含其他分站" }
            if (races.isEmpty()) {
                require(root.optString("total", "0") == "0")
                return emptyList()
            }
            val r = races.single()
            require(r.getString("season").toInt() == race.season && r.getString("round").toInt() == race.round)
            require(race.circuitId == null || r.getJSONObject("Circuit").getString("circuitId") == race.circuitId)
            val rows = r.getJSONArray("QualifyingResults").objects()
            root.text("total")?.toIntOrNull()?.let { require(it == rows.size) { "排位响应不完整" } }
            return rows.map { row ->
                val d = row.getJSONObject("Driver"); val c = row.getJSONObject("Constructor")
                QualifyingEntry(row.text("position"), d.getString("driverId"), "${d.getString("givenName")} ${d.getString("familyName")}",
                    c.text("constructorId"), c.getString("name"), row.text("Q1"), row.text("Q2"), row.text("Q3"))
            }.sortedBy { it.position?.toIntOrNull() ?: Int.MAX_VALUE }.also { entries ->
                require(entries.map { it.driverId }.distinct().size == entries.size)
            }
        }
    }
}
