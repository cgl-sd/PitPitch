package com.example.pitpitch.data

import android.content.Context
import org.json.JSONObject
import com.example.pitpitch.data.repository.localBackend

/** Exact provider IDs; cars and liveries never fall back to another season or team. */
object F1Media {
    private fun catalog(context: Context): JSONObject = localBackend(context).references.value.f1Media
    fun asset(context: Context, role: String, entity: String?, season: Int? = null): String? {
        if (entity.isNullOrBlank()) return null
        val key = if (role == "circuit") "$role/$entity" else "$role/$season/$entity"
        val file = catalog(context).optJSONObject(key)?.optString("file")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+\\.(webp|png|svg)")) } ?: return null
        return "file:///android_asset/f1-media/$file"
    }
    fun layout(context: Context, race: F1Race): String? {
        val record = catalog(context).optJSONObject("layout/${race.season}/${race.id}") ?: return null
        if (race.circuitId.isNullOrBlank() || record.optString("circuitId") != race.circuitId ||
            record.optInt("season") != race.season || record.optInt("round") != race.round ||
            record.optString("entity") != race.id) return null
        return asset(context, "layout", race.id, race.season)
    }
}
