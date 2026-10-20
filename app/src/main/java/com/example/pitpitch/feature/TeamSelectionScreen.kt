package com.example.pitpitch.feature

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.localBackend
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.TeamBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun TeamSelectionScreen(state: SportsViewModel.State, retry: () -> Unit, select: (FootballTeam) -> Unit) {
    val references by localBackend(LocalContext.current).references.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    // The value is assigned after the background computation; lint does not follow assignment through withContext.
    @Suppress("ProduceStateDoesNotAssignValue")
    val entries by produceState<List<TeamSelectionEntry>?>(null, state.remoteMatches, references) {
        val resolved = withContext(Dispatchers.Default) {
            val teams = state.remoteMatches.flatMap { match -> listOfNotNull(
                match.homeTeamId?.let { FootballTeam(it, match.home, match.homeBadgeUrl) },
                match.awayTeamId?.let { FootballTeam(it, match.away, match.awayBadgeUrl) },
            ) }
            val dictionary = references.names.optJSONObject("teams")
            val names = teams.distinctBy { it.id }.mapNotNull { team ->
                dictionary?.optJSONObject(team.id.toString())?.optString("name")?.takeIf(String::isNotBlank)?.let { team.id to it }
            }.toMap()
            teamSelectionEntries(teams, names)
        }
        value = resolved
    }
    val search = query.trim()
    val visibleEntries = remember(entries, search) { entries.orEmpty().filter { it.matchesTeamSearch(search) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextField(query, { query = it }, Modifier.fillMaxWidth().testTag("team-search"),
            placeholder = { Text("搜索球队名称", fontSize = 15.sp) }, singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFFF1F6F3), unfocusedContainerColor = Color(0xFFF1F6F3),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = FootballAccent, focusedLeadingIconColor = FootballAccent, unfocusedLeadingIconColor = FootballAccent),
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(22.dp)) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { query = "" }, modifier = Modifier.testTag("team-search-clear")) {
                    Icon(Icons.Outlined.Close, "清除搜索")
                }
            }) else null)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            key(search) {
                val listState = rememberLazyListState()
                val initial by remember(listState, visibleEntries, state.footballError) { derivedStateOf {
                    val leading = if (state.footballError != null) 1 else 0
                    visibleEntries.getOrNull((listState.firstVisibleItemIndex - leading).coerceAtLeast(0))?.initial
                } }
                Page(state = listState, padding = PaddingValues(bottom = 8.dp), spacing = 0.dp) {
                    if (state.footballError != null) item {
                        StatePanel(if (state.footballCached) "使用缓存球队" else "球队读取失败", state.footballError, retry = retry)
                    }
                    if (entries != null && !state.footballLoading && entries.orEmpty().isEmpty()) item { StatePanel("暂无可选球队", retry = retry) }
                    else if (entries != null && visibleEntries.isEmpty() && search.isNotEmpty()) item { StatePanel("未找到球队") }
                    items(visibleEntries, key = { it.team.id }) { entry ->
                        val team = entry.team
                        TextButton(onClick = { select(team) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("team-${team.id}")) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TeamBadge(entry.displayName, team.id, team.badgeUrl, size = 32.dp)
                                Text(entry.displayName, Modifier.weight(1f))
                                if (team.id == state.homeTeam?.id) Text("已选")
                            }
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(visible = listState.isScrollInProgress && initial != null,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                    enter = fadeIn(tween(80)), exit = fadeOut(tween(160))) {
                    Surface(shape = RoundedCornerShape(14.dp), color = PanelSurface, shadowElevation = 2.dp) {
                        Box(Modifier.size(48.dp, 52.dp).testTag("team-scroll-initial"), contentAlignment = Alignment.Center) {
                            Text(initial.orEmpty(), color = FootballAccent, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
