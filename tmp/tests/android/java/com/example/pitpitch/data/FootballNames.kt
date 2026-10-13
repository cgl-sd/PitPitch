package com.example.pitpitch.data

import android.content.Context
import org.json.JSONObject
import com.example.pitpitch.data.repository.localBackend

/** A database-preloaded display dictionary; provider names remain untouched. */
class FootballNames private constructor(private val catalog: JSONObject) {
    fun team(id: Int?, original: String): String = id?.let {
        catalog.optJSONObject("teams")?.optJSONObject(it.toString())?.optString("name")?.takeIf(String::isNotBlank)
    } ?: original
    fun venue(original: String): String = catalog.optJSONObject("venues")?.optJSONObject(original)
        ?.optString("name")?.takeIf(String::isNotBlank) ?: original
    companion object {
        fun get(context: Context): FootballNames = FootballNames(localBackend(context).references.value.names)
    }
}
