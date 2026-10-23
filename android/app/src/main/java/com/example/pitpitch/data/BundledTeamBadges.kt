package com.example.pitpitch.data

import android.content.Context
import com.example.pitpitch.data.repository.localBackend

/** Exact URL index preloaded from Room. List rows never issue database queries. */
object BundledTeamBadges {
    fun asset(context: Context, sourceUrl: String?, teamId: Int? = null): String? {
        val refs = localBackend(context).references.value
        val path = secureBadgeUrl(sourceUrl)?.let { refs.badges[it] } ?: teamId?.let { refs.teamBadges[it] }
        return path?.let { "file:///android_asset/$it" }
    }
}
