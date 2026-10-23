package com.example.pitpitch.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.example.pitpitch.R
import com.example.pitpitch.data.secureBadgeUrl
import com.example.pitpitch.data.BundledTeamBadges
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Preserve the crest shape, using exact catalog identity rather than guessing by name. */
@Composable fun TeamBadge(team: String, teamId: Int?, url: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val safeUrl = secureBadgeUrl(url)
    val references by com.example.pitpitch.data.repository.localBackend(LocalContext.current).references.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val model = remember(safeUrl, teamId, context, references) { BundledTeamBadges.asset(context, safeUrl, teamId) ?: safeUrl }
    var loaded by remember(model) { mutableStateOf(false) }
    val placeholder = painterResource(R.drawable.ic_team_placeholder)
    AsyncImage(
        model = model,
        contentDescription = if (loaded) "$team 队徽" else "$team 队徽占位图",
        modifier = modifier.size(size), contentScale = ContentScale.Fit,
        placeholder = placeholder, error = placeholder, fallback = placeholder,
        onLoading = { loaded = false }, onError = { loaded = false }, onSuccess = { loaded = true },
    )
}
