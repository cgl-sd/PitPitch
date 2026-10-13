package com.example.pitpitch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.pitpitch.data.repository.localBackend

@Composable fun footballTeamName(id: Int?, original: String): String {
    val context = LocalContext.current
    val references by localBackend(context).references.collectAsState()
    return references.names.optJSONObject("teams")?.optJSONObject(id?.toString().orEmpty())?.optString("name")?.takeIf(String::isNotBlank) ?: original
}
@Composable fun footballVenueName(original: String): String {
    val references by localBackend(LocalContext.current).references.collectAsState()
    return references.names.optJSONObject("venues")?.optJSONObject(original)?.optString("name")?.takeIf(String::isNotBlank) ?: original
}
