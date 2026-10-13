package com.example.pitpitch.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.Filters
import java.time.ZoneId

@Composable fun TeamProfileScreen(state: SportsViewModel.State, team: FootballTeam, year: Int, league: String,
    zone: ZoneId, openMatch: (String) -> Unit, refresh: () -> Unit) {
    var section by rememberSaveable(team.id, year) { mutableIntStateOf(0) }
    val key = "$year|$league|${team.id}"
    val response = state.squads[key]
    val positions = listOf("前锋", "中场", "后卫", "门将")
    var competition by rememberSaveable(team.id, year) { mutableStateOf("all") }
    var side by rememberSaveable(team.id, year) { mutableIntStateOf(0) }
    val seasonMatches = if (state.footballSeason == year) state.matches else emptyList()
    val allMatches = remember(seasonMatches, team.id) { teamProfileMatches(seasonMatches, team.id, null, 0) }
    val competitions = remember(allMatches) { footballCompetitions.filter { entry -> allMatches.any { it.competitionId == entry.id } } }
    val matches = remember(seasonMatches, team.id, competition, side, zone) {
        teamProfileMatches(seasonMatches, team.id, competition.takeUnless { it == "all" }, side)
            .sortedBy { homeMatchTime(it, zone) }
    }
    val fixtureMonths = remember(matches, zone) { matches.map { teamFixtureMonth(it, zone) } }
    val profileLeague = remember(allMatches, league) {
        footballCompetitions.firstOrNull { !it.european && allMatches.any { match -> match.competitionId == it.id } }?.id ?: league
    }
    val standing = if (state.footballSeason == year) teamPublishedStanding(state.footballTables[profileLeague]?.data.orEmpty(), team.id) else null
    val venue = remember(allMatches, team.id, profileLeague) { teamHomeFixtureVenue(allMatches, team.id, profileLeague) }
    val rankingScope = if (state.footballSeason == year && profileLeague.startsWith("uefa.") && year < 2024)
        state.footballTables[profileLeague]?.data?.singleOrNull { table -> table.entries.any { it.team.id == team.id } }?.name else null
    Page(spacing = 8.dp) {
        item { TeamProfileHeader(team, year, profileLeague, standing, venue, rankingScope) }
        item { ScheduleTabs(listOf("赛程", "队员"), section, "team-profile-section", { section = it }) }
        if (section == 0) {
            state.footballError?.let { item { StatePanel("赛程读取未成功", it) } }
            if (competitions.size > 1) item { Filters(competitions.map { it.id }, competition,
                { competition = if (competition == it) "all" else it }, label = { id -> competitions.find { it.id == id }?.title ?: id }) }
            item { ScheduleTabs(listOf("全部", "主场", "客场"), side, "team-profile-side", { side = it }) }
            teamScheduleItems(matches, fixtureMonths, zone, "season", openMatch)
            if (matches.isEmpty() && !state.footballLoading) item { StatePanel("暂无球队赛程", "当前赛事、主客场或赛季没有已提供的比赛。") }
        } else {
            state.squadErrors[key]?.let { item { StatePanel("队员读取失败", it, retry = refresh) } }
            if (response != null) {
                response.data.coach?.let { item { Text("主教练：$it") } }
                item { Text("球员 / 位置                 进球  出场  助攻", color = Muted, fontSize = 12.sp) }
                positions.forEach { position ->
                    val players = response.data.players.filter { it.position == position }
                    item { Box(Modifier.testTag("team-position-$position")) { Heading(position) } }
                    items(players, key = { "player-${it.id}" }) { PlayerRow(it) }
                    if (players.isEmpty()) item { Text("暂无${position}记录", color = Muted, fontSize = 12.sp) }
                }
                val unknown = response.data.players.filter { it.position !in positions }
                if (unknown.isNotEmpty()) {
                    item { Heading("其他队员") }
                    items(unknown, key = { "player-${it.id}" }) { PlayerRow(it) }
                }
            } else if (key !in state.squadLoading && state.squadErrors[key] == null) item { StatePanel("暂无队员资料", "该赛事或赛季未提供球队名单。") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.teamScheduleItems(matches: List<FootballMatch>, months: List<java.time.YearMonth?>, zone: ZoneId, prefix: String, open: (String) -> Unit) {
    var previous: java.time.YearMonth? = null
    matches.forEachIndexed { index, match ->
        val month = months[index]
        if (index == 0 || month != previous) item(key = "$prefix-month-$index") {
            Surface(color = PanelSurface, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                Text(month?.let { "${it.year}年${it.monthValue}月" } ?: "—",
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("team-month-$prefix-$index"),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        previous = month
        item(key = "$prefix-${match.id}") {
            Text("${match.competition} · ${footballMatchStageLabel(match)}", color = Muted, fontSize = 11.sp)
            FootballFixtureRow(match, zone) { open(match.id) }
        }
    }
}
