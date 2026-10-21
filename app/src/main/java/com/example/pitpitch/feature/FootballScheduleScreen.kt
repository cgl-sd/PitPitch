package com.example.pitpitch.feature


import com.example.pitpitch.ui.components.footballTeamName
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FootballScreen(state: SportsViewModel.State, zone: ZoneId, onOpen: (String) -> Unit,
    refresh: () -> Unit = {}, season: (Int) -> Unit = {}, competition: (String) -> Unit = {}, openTeam: (Int) -> Unit = {}, loadLeaderboards: () -> Unit = {}, refreshStandings: () -> Unit = {},
    scheduleRequest: Int = 0) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val leagues = footballCompetitions.map { it.title }
    val selectedCompetition = footballCompetitions.first { it.id == state.footballCompetition }
    val league = selectedCompetition.title
    var phase by rememberSaveable(state.footballSeason, state.footballCompetition) { mutableIntStateOf(0) }
    var phaseAutoPositioned by rememberSaveable(state.footballSeason, state.footballCompetition) { mutableStateOf(false) }
    var playerMode by rememberSaveable(state.footballCompetition, state.footballSeason) { mutableIntStateOf(0) }
    val leaderboardKey = "${state.footballSeason}|${state.footballCompetition}"
    val leaderboardResponse = state.leaderboards[leaderboardKey]
    val leaderboardEntries = if (section == 2) remember(leaderboardResponse?.data, playerMode) {
        leaderboardResponse?.data.orEmpty().sortedByDescending { if (playerMode == 1) it.assists else it.goals }
            .take(50).withIndex().toList()
    } else emptyList()
    LaunchedEffect(section, state.footballCompetition, state.footballSeason) {
        if (section == 2) loadLeaderboards()
    }
    // Standings belong to the league phase only, so the phase selector never hides them.
    // Re-entering the section refreshes once; recreation (rotation, detail return) reuses the request.
    var standingsRequested by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(section, state.footballCompetition, state.footballSeason) {
        if (section == 1) {
            val key = "${state.footballSeason}|${state.footballCompetition}"
            // A new process has no ViewModel data even when saveable section state survives.
            if (standingsRequested != key || state.footballTables[state.footballCompetition] == null) {
                standingsRequested = key
                refreshStandings()
            }
        } else standingsRequested = null
    }
    LaunchedEffect(Unit) { if (section !in 0..2) section = 0 }
    // An entry from the home card asks for this competition's schedule once; later returns keep the chosen section.
    var consumedScheduleRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(scheduleRequest) {
        if (scheduleRequest > consumedScheduleRequest) {
            consumedScheduleRequest = scheduleRequest
            section = 0
        }
    }
    val now = Instant.now()
    // Once the league phase has no upcoming fixture, opening a European competition shows the knockout bracket.
    LaunchedEffect(selectedCompetition.id, state.footballSeason, state.matches.isNotEmpty()) {
        if (!selectedCompetition.european) return@LaunchedEffect
        if (phase !in 0..1) phase = 0
        if (phaseAutoPositioned || state.matches.isEmpty()) return@LaunchedEffect
        val leagueStageOpen = filterMatches(state.matches, league, 0, "").any {
            isEuropeanGroupStage(it.stage) && it.status.contains("待赛") && homeMatchTime(it, zone).isAfter(now)
        }
        if (!leagueStageOpen) phase = 1
        phaseAutoPositioned = true
    }
    val competitionMatches = remember(state.matches, league) { filterMatches(state.matches, league, 0, "") }
    val phaseMatches = remember(competitionMatches, selectedCompetition.european, phase) {
        competitionMatches.filter {
            !selectedCompetition.european || when (phase) {
                // Knockout includes the qualifying rounds; there is no third phase any more.
                1 -> !isEuropeanGroupStage(it.stage)
                else -> isEuropeanGroupStage(it.stage)
            }
        }
    }
    val stages = remember(phaseMatches) { footballStages(phaseMatches) }
    val visible = remember(phaseMatches, zone) { phaseMatches.sortedBy { homeMatchTime(it, zone) } }
    val next = nextFootballMatch(visible, now, zone)
    val current = next?.let(::footballRoundKey) ?: stages.lastOrNull()
    val context = "${state.footballSeason}|${selectedCompetition.id}|$section|$phase"
    val leadingItems = if (state.footballError != null) 1 else 0
    val initialIndex = remember(visible, next?.id, section, leadingItems) {
        if (section == 0 && visible.isNotEmpty()) leadingItems +
            (if (next == null) visible.lastIndex else visible.indexOfFirst { it.id == next.id }.coerceAtLeast(0))
        else 0
    }
    val listState = rememberSaveable(context, saver = LazyListState.Saver) { LazyListState(initialIndex, 0) }
    var positionedContext by rememberSaveable { mutableStateOf<String?>(null) }
    var openingDetail by remember { mutableStateOf(false) }
    val selectedStage by remember(listState, visible, current, leadingItems, context) { derivedStateOf {
        val visibleIndex = (listState.firstVisibleItemIndex - leadingItems).coerceAtLeast(0)
        val visibleStage = visible.getOrNull(visibleIndex)?.let(::footballRoundKey)
        if (!openingDetail && positionedContext != context) current else visibleStage ?: current
    } }
    val index = stages.indexOf(selectedStage)
    fun jumpToRound(round: String?) {
        val target = visible.indexOfFirst { footballRoundKey(it) == round }
        if (target >= 0) listState.requestScrollToItem(leadingItems + target)
    }
    // Initial data can arrive after this context was created. Request its position before
    // the next measure, rather than rendering at a stale index and scrolling in a coroutine.
    SideEffect {
        if (!openingDetail && positionedContext != context && (section != 0 || visible.isNotEmpty())) {
            if (listState.firstVisibleItemIndex != initialIndex || listState.firstVisibleItemScrollOffset != 0)
                listState.requestScrollToItem(initialIndex)
            positionedContext = context
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("football-page")) {
        Box(Modifier.fillMaxWidth().testTag("football-leagues")) {
        Filters(leagues, league, { footballCompetitions.find { c -> c.title == it }?.let { c -> competition(c.id) } })
        }
        Spacer(Modifier.height(4.dp))
        run {
            Row(Modifier.fillMaxWidth().testTag("football-tabs"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).alignByBaseline()) { ScheduleTabs(listOf("赛程", "积分", "球员"), section, "football-section", { section = it }) }
                ScheduleMenu("${state.footballSeason.toString().takeLast(2)}–${(state.footballSeason + 1).toString().takeLast(2)}", "football-season",
                    footballArchiveYears().map { it.toString() to "$it / ${it+1}" }, { season(it.toInt()) }, Modifier.alignByBaseline())
            }
        }
        if (selectedCompetition.european && section <= 1) {
            Spacer(Modifier.height(2.dp))
            Segments(listOf(if (state.footballSeason < 2024) "小组赛" else "联赛阶段", "淘汰赛"), phase, { phase = it }, modifier = Modifier.testTag("european-phase"), pill = true)
        }
        // Knockouts have no provider rounds: the bracket is one continuous list.
        if (section == 0 && (!selectedCompetition.european || phase == 0)) Surface(color = Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ jumpToRound(stages[index - 1]) }, Modifier.testTag("football-prev-round"), enabled = index > 0,
                    colors = ButtonDefaults.textButtonColors(contentColor = FootballAccent)) { Text("‹ 上一轮", fontSize = 12.sp) }
                TextButton({ jumpToRound(selectedStage) }, Modifier.weight(1f).testTag("football-round")) {
                    Text(selectedStage?.let(::footballRoundNavigationLabel) ?: "轮次未提供", fontSize = 13.sp, color = PrimaryText)
                }
                TextButton({ jumpToRound(stages[index + 1]) }, Modifier.testTag("football-next-round"), enabled = index >= 0 && index < stages.lastIndex,
                    colors = ButtonDefaults.textButtonColors(contentColor = FootballAccent)) { Text("下一轮 ›", fontSize = 12.sp) }
            }
        }
        Box(Modifier.weight(1f)) { key(context) { Page(state = listState, padding = PaddingValues(vertical = 8.dp)) {
        if (state.footballError != null) item { StatePanel("更新失败", state.footballError, retry = refresh) }
        when (section) {
            0 -> {
                if (visible.isEmpty() && !state.footballLoading) item { StatePanel("暂无赛程") }
                items(visible, key = { it.id }) { match ->
                    if (selectedCompetition.european && phase == 1) Text(
                        match.stage + when (match.leg) { 1 -> " · 首回合"; 2 -> " · 次回合"; else -> "" },
                        color = Muted, fontSize = 12.sp)
                    FootballFixtureRow(match, zone) {
                        openingDetail = true
                        onOpen(match.id)
                    }
                }
            }

            1 -> {
                val response = state.footballTables[state.footballCompetition]
                if (state.footballTableError != null) item { StatePanel("积分更新失败", state.footballTableError, retry = refreshStandings) }
                val tableLoading = "${state.footballSeason}|${state.footballCompetition}" in state.footballTableLoading
                if (!state.footballLoading && !tableLoading && response?.data.orEmpty().isEmpty()) item { StatePanel("暂无联赛积分", "数据源未提供该赛季积分；不会按部分赛程推算官方排名。") }
                response?.data.orEmpty().forEach { table ->
                    if (selectedCompetition.european) item { Heading(table.name) }
                    item { FootballPointsTable(table, state.footballCompetition, state.footballSeason, openTeam) }
                }
                if (response?.data.orEmpty().isNotEmpty()) item { FootballRules(state.footballCompetition, state.footballSeason) }
            }
            2 -> {
                item { Segments(listOf("进球榜", "助攻榜"), playerMode, { playerMode = it }, Modifier.testTag("leaderboard-mode"), pill = true) }
                if (state.leaderboardErrors[leaderboardKey] != null) item { StatePanel("榜单读取失败", state.leaderboardErrors[leaderboardKey], retry = loadLeaderboards) }
                if (leaderboardResponse != null && leaderboardEntries.isEmpty()) item { StatePanel("暂无球员榜单") }
                items(leaderboardEntries, key = { "leader-${it.index}" }) { (index, entry) ->
                    Row(Modifier.fillMaxWidth().testTag("leaderboard-row-${index + 1}").padding(vertical = 12.dp)) {
                        Text("${index + 1}", Modifier.width(28.dp), color = Muted)
                        Column(Modifier.weight(1f)) { Text(entry.name); Text(footballTeamName(entry.teamId, entry.team), color = Muted, fontSize = 11.sp) }
                        Text("${(if (playerMode == 1) entry.assists else entry.goals) ?: "—"}", fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
        } } }
    }
}
