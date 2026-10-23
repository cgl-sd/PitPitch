package com.example.pitpitch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pitpitch.data.PENDING_TEAM_NAME
import com.example.pitpitch.data.PENDING_VENUE_NAME
import com.example.pitpitch.data.repository.localBackend

@Composable fun footballTeamName(id: Int?, original: String): String {
    val context = LocalContext.current
    val references by localBackend(context).references.collectAsStateWithLifecycle()
    val localized = references.names.optJSONObject("teams")?.optJSONObject(id?.toString().orEmpty())?.optString("name")?.takeIf(String::isNotBlank)
    return (localized ?: original).takeIf(String::isNotBlank) ?: PENDING_TEAM_NAME
}
@Composable fun footballVenueName(original: String): String {
    val references by localBackend(LocalContext.current).references.collectAsStateWithLifecycle()
    val localized = references.names.optJSONObject("venues")?.optJSONObject(original)?.optString("name")?.takeIf(String::isNotBlank)
    val usable = original.takeIf { it.isNotBlank() && it != "场地未提供" && it.uppercase() != "TBC" && it.uppercase() != "TBD" }
    return localized ?: usable ?: PENDING_VENUE_NAME
}
