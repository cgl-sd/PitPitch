package com.example.pitpitch.feature

import com.example.pitpitch.ui.components.footballTeamName
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.FootballTeam
import com.example.pitpitch.ui.Muted
import com.example.pitpitch.ui.components.TeamBadge

@Composable fun TeamSelectionScreen(state: SportsViewModel.State, retry: () -> Unit, select: (FootballTeam) -> Unit) {
    val teams = remember(state.remoteMatches) {
        state.remoteMatches.flatMap { match -> listOfNotNull(
            match.homeTeamId?.let { FootballTeam(it, match.home, match.homeBadgeUrl) },
            match.awayTeamId?.let { FootballTeam(it, match.away, match.awayBadgeUrl) },
        ) }.distinctBy { it.id }.sortedBy { it.name }
    }
    Page {
        item { Heading("选择你的主队", "${state.footballSeason}/${state.footballSeason + 1} 赛季") }
        item { Text("选择后，底部队徽打开球队详情；可在设置中更换主队。", color = Muted) }
        if (state.footballError != null) item { StatePanel(if (state.footballCached) "使用缓存球队" else "球队读取失败", state.footballError, retry = retry) }
        if (!state.footballLoading && teams.isEmpty()) item { StatePanel("暂无可选球队", "当前数据源没有提供球队；不使用演示队名替代。", retry = retry) }
        items(teams, key = { it.id }) { team ->
            TextButton(onClick = { select(team) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("team-${team.id}")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TeamBadge(footballTeamName(team.id, team.name), team.id, team.badgeUrl, size = 32.dp)
                    Text(footballTeamName(team.id, team.name), Modifier.weight(1f))
                    if (team.id == state.homeTeam?.id) Text("已选")
                }
            }
        }
    }
}
