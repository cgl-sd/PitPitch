package com.example.pitpitch.feature

import com.example.pitpitch.ui.components.footballTeamName
import com.example.pitpitch.ui.components.footballVenueName
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.TeamBadge
import com.example.pitpitch.data.*
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable fun Page(spacing: androidx.compose.ui.unit.Dp = 4.dp, state: LazyListState = rememberLazyListState(), padding: PaddingValues = PaddingValues(16.dp), content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}
@Composable fun Heading(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}
@Composable fun Panel(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = PanelSurface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable fun StatePanel(title: String, message: String? = null, loading: Boolean = false, retry: (() -> Unit)? = null) {
    if (loading) return
    Panel {
        Text(title, fontWeight = FontWeight.SemiBold)
        if (message != null) Text(message, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        if (retry != null) OutlinedButton(onClick = retry) { Text("重试") }
    }
}
@Composable fun FavoriteButton(selected: Boolean, name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(if (selected) Icons.Filled.Star else Icons.Outlined.StarBorder,
        (if (selected) "取消收藏 " else "收藏 ") + name, tint = if (selected) FootballGreen else Muted) }
}
@Composable fun DemoNotice() { Text("演示数据", color = FootballGreen, fontSize = 12.sp) }
@Composable fun MatchCard(match: FootballMatch, zone: ZoneId, selected: Boolean? = null, onFavorite: () -> Unit = {}, onOpen: (() -> Unit)? = null, onTeam: ((Int) -> Unit)? = null) {
    Box(Modifier.testTag("match-${match.id}")) { OutlinedCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(match.competition, Modifier.weight(1f), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                Text(match.kickoffUtc?.let { displayInstant(it, zone) } ?: "${match.date} · 开球时间待定",
                    color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                if (selected != null) FavoriteButton(selected, "${footballTeamName(match.homeTeamId, match.home)} 对 ${footballTeamName(match.awayTeamId, match.away)}", onFavorite)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MatchTeamBadge(match.homeTeamId, footballTeamName(match.homeTeamId, match.home), match.homeBadgeUrl, "home", onTeam)
                    Text(footballTeamName(match.homeTeamId, match.home), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Column(Modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(match.score, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    Text(matchScoreStatus(match), Modifier.testTag("match-score-status"), color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MatchTeamBadge(match.awayTeamId, footballTeamName(match.awayTeamId, match.away), match.awayBadgeUrl, "away", onTeam)
                    Text(footballTeamName(match.awayTeamId, match.away), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Text("${footballVenueName(match.stadium)} · ${footballMatchStageLabel(match)}", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    } }
}
@Composable private fun MatchTeamBadge(id: Int?, name: String, url: String?, side: String, onTeam: ((Int) -> Unit)?) {
    val target = if (id != null && onTeam != null) Modifier.clickable(
        onClickLabel = "查看$name 球队详情", role = androidx.compose.ui.semantics.Role.Button
    ) { onTeam(id) } else Modifier
    Box(Modifier.size(48.dp).testTag("match-team-$side").then(target), contentAlignment = Alignment.Center) {
        TeamBadge(name, id, url, size = 32.dp)
    }
}
fun displayInstant(value: String?, zone: ZoneId): String = value?.let {
    runCatching { Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
} ?: "暂无更新时间"
