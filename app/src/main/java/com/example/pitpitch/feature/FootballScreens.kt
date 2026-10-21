package com.example.pitpitch.feature

import com.example.pitpitch.ui.components.footballTeamName
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MatchDetail(match: FootballMatch, zone: ZoneId, selected: Boolean?, onFavorite: () -> Unit, fetched: String? = null, cached: Boolean = false, detail: RemoteSnapshot<FootballDetail>? = null, loading: Boolean = false, error: String? = null, retry: () -> Unit = {}, onTeam: ((Int) -> Unit)? = null) {
    var tab by rememberSaveable(match.id) { mutableIntStateOf(0) }
    var side by rememberSaveable(match.id) { mutableIntStateOf(0) }
    val unavailable = match.status.contains("延期") || match.status.contains("取消")
    val selectedLineup = detail?.data?.lineups?.find { it.teamId == if (side == 0) match.homeTeamId else match.awayTeamId }
    val publishedRows = remember(selectedLineup) { selectedLineup?.let(::publishedLineupRows) }
    val rows = remember(selectedLineup, publishedRows) { publishedRows ?: selectedLineup?.let(::schematicLineupRows) }
    key(tab) {
    Page(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        if (match.isDemo) item { DemoNotice() }
        item { MatchCard(match, zone, selected, onFavorite, onTeam = onTeam) }
        item { ScheduleTabs(listOf("数据与事件", "首发"), tab, "match-detail-section", { tab = it }) }
        when (tab) {
            1 -> {
                item { Segments(listOf(footballTeamName(match.homeTeamId, match.homeShort), footballTeamName(match.awayTeamId, match.awayShort)), side, { side = it },
                    Modifier.testTag("match-lineup-side")) }
                if (!match.isDemo) {
                    val lineup = selectedLineup
                    if (loading && lineup == null) item { MatchDetailPlaceholder("lineup") }
                    else if (lineup == null || (lineup.starters.isEmpty() && lineup.substitutes.isEmpty())) item { StatePanel(
                        if (match.status.contains("待赛")) "首发尚未公布" else "暂无该场首发记录",
                        "供应商未提供可显示的阵容，不生成预测首发。", retry = if (!loading) retry else null) }
                    else {
                        if (rows != null) item {
                            val formation = lineup.formation?.takeIf { it.isNotBlank() }?.let { "阵型 $it" }
                            Text(if (publishedRows != null) formation.orEmpty()
                                else listOfNotNull(formation, "站位示意").joinToString(" · "), color = Muted, fontSize = 12.sp)
                        }
                        if (rows != null) item { LineupPitch(rows, lineup.teamId) }
                        else item { Text("首发尚未公布", color = Muted, fontSize = 12.sp) }
                        item { Heading("替补 · ${lineup.substitutes.size} 人") }
                        items(lineup.substitutes, key = { "substitute-${it.id}" }) { PlayerRow(it) }
                        if (lineup.unknown.isNotEmpty()) {
                            item { Heading("其他队员") }
                            items(lineup.unknown, key = { "unknown-${it.id}" }) { PlayerRow(it) }
                        }
                    }
                } else if (!match.lineupAvailable || unavailable) item { StatePanel(if (unavailable) "本场${match.status.removePrefix("演示")}" else if (match.isDemo) "首发尚未公布" else "暂无首发", if (match.isDemo) "暂无官方或预计阵容，不使用占位名单冒充首发。" else "此免费数据源不提供首发；无法判断是否已公布。") }
                else {
                    item { Heading(if (side == 0) match.home else match.away, "4–3–3 · 演示名单，非官方首发") }
                    items(listOf("门将", "左后卫", "中卫 A", "中卫 B", "右后卫", "中场 A", "中场 B", "中场 C", "左边锋", "中锋", "右边锋").withIndex().toList(), key = { "player-${it.index}" }) { player ->
                        Panel { Text("${player.index + 1} 号 · ${if (side == 0) match.homeShort else match.awayShort} ${player.value}") }
                    }
                    item { StatePanel("替补名单未提供", "示例中仅提供位置与球衣编号。") }
                }
            }
            else -> {
                item { Heading("技术统计") }
                val statistics = compactMatchStatistics(detail?.data?.statistics.orEmpty())
                if (!match.isDemo && statistics.isNotEmpty()) item { MatchStatistics(match, statistics) }
                else if (loading) item { MatchDetailPlaceholder("statistics") }
                else item { StatePanel("暂无技术统计", "缺失值不会显示为 0。") }
                item { Heading("比赛事件") }
                val events = compactFootballEvents(detail?.data?.events.orEmpty())
                if (!match.isDemo && events.isNotEmpty()) {
                    matchTimeline(match, events)
                } else if (loading) item { MatchDetailPlaceholder("events") }
                else item { StatePanel("暂无比赛事件", "尚无已发布事件。") }

            }
        }
        if (!match.isDemo && error != null) item { StatePanel("比赛详情读取失败", error, retry = retry) }
    }
    }
}

@Composable private fun MatchDetailPlaceholder(kind: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("match-placeholder-$kind"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(3) { index ->
            Box(Modifier.fillMaxWidth(if (index == 1) .7f else 1f).height(14.dp)
                .background(Color(0xFFF0F2F5), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)))
        }
    }
}
