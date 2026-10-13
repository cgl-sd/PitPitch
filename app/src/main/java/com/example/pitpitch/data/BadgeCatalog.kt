package com.example.pitpitch.data

import android.content.Context
import com.example.pitpitch.data.repository.localBackend

/** Legacy artwork remains indexed for compatibility, independently of provider URL crests. */
object BadgeCatalog {
    fun uri(context: Context, teamId: Int?, url: String?): String? {
        val path = teamId?.let { localBackend(context).references.value.legacyBadges[it.toString()] }
        return path?.let { "file:///android_asset/$it" } ?: secureBadgeUrl(url)
    }
}
