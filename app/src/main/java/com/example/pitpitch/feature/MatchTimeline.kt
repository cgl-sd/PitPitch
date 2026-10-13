package com.example.pitpitch.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.TeamBadge
import com.example.pitpitch.ui.components.footballTeamName

@Composable internal fun MatchTimelineHeader(match: FootballMatch) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TimelineTeam(match.homeTeamId, match.homeShort, match.homeBadgeUrl, Modifier.weight(1f))
        Spacer(Modifier.width(56.dp))
        TimelineTeam(match.awayTeamId, match.awayShort, match.awayBadgeUrl, Modifier.weight(1f))
    }
}
@Composable private fun TimelineTeam(id: Int?, name: String, badge: String?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TeamBadge(footballTeamName(id, name), id, badge, size = 20.dp)
        Text(footballTeamName(id, name), Modifier.padding(start = 6.dp), fontSize = 12.sp)
    }
}

@Composable internal fun MatchEventRow(event: FootballDetailEvent, match: FootballMatch, first: Boolean, last: Boolean) {
    val node = event.type in setOf("kickoff", "halftime", "start-2nd-half", "end-regular-time", "end-extra-time", "end-match", "stoppage-time")
    Box(Modifier.fillMaxWidth().testTag("detail-event-${event.id}")) {
        Canvas(Modifier.matchParentSize()) {
            drawLine(Color(0xFFF0F1F3), Offset(size.width / 2, if (first) size.height / 2 else 0f),
                Offset(size.width / 2, if (last) size.height / 2 else size.height), 1.dp.toPx())
        }
        if (node) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (event.type == "kickoff" || event.type == "end-match") {
                    Icon(Icons.Outlined.Sports, if (event.type == "kickoff") "开赛" else "结束",
                        Modifier.size(22.dp).background(Color.White).testTag("event-symbol-${event.id}"), tint = PrimaryText)
                } else {
                    val label = when (event.type) {
                        "halftime" -> "上半场结束"
                        "start-2nd-half" -> "下半场开始"
                        "end-regular-time" -> if (match.status == "完场") "下半场结束" else "常规时间结束"
                        "end-extra-time" -> "加时赛结束"
                        else -> event.text
                    }
                    val score = footballEventScore(event, match)
                    Text(if (score != null) "$label $score" else label, Modifier.background(Color.White).padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 14.sp, color = PrimaryText, textAlign = TextAlign.Center)
                    if (event.type == "end-regular-time" && match.status == "完场") Icon(Icons.Outlined.Sports, "结束",
                        Modifier.padding(top = 6.dp).size(22.dp).background(Color.White), tint = PrimaryText)
                }
            }
        } else {
            val home = event.teamId != null && event.teamId == match.homeTeamId
            val away = event.teamId != null && event.teamId == match.awayTeamId
            if (!home && !away) EventCard(event, match, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            else Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { if (home) EventCard(event, match, Modifier.fillMaxWidth()) }
                Text(event.clock.ifBlank { "—" }, Modifier.width(56.dp).background(Color.White).padding(vertical = 6.dp)
                    .testTag("event-clock-${event.id}"), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Box(Modifier.weight(1f)) { if (away) EventCard(event, match, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable private fun EventCard(event: FootballDetailEvent, match: FootballMatch, modifier: Modifier) {
    val red = event.type.contains("red-card") || event.type.contains("second-yellow")
    val label = when {
        event.type.contains("second-yellow") -> "两黄变红"
        red -> "红牌"
        event.type.contains("own-goal") -> "乌龙球"
        event.type == "penalty---scored" -> "点球进球"
        else -> "进球"
    }
    Surface(modifier.testTag("event-content-${event.id}"), color = Color(0xFFF7F8FA), shape = RoundedCornerShape(4.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(18.dp).testTag("event-symbol-${event.id}"), contentAlignment = Alignment.Center) {
                if (red) Box(Modifier.size(10.dp, 14.dp).background(RaceRed, RoundedCornerShape(1.dp)))
                else Icon(Icons.Outlined.SportsSoccer, label, Modifier.size(18.dp), tint = PrimaryText)
            }
            val score = footballEventScore(event, match)
            Column(Modifier.weight(1f)) {
                EventTitle(if (event.players.isNotEmpty()) "${event.players.first()} $label" else label, "event-member-${event.id}")
                if (score != null) Text("[$score]", fontSize = 13.sp, color = Color(0xFF1685D8))
            }
        }
    }
}

@Composable private fun EventTitle(title: String, tag: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val style = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, color = PrimaryText)
        val width = measurer.measure(title, style, softWrap = false).size.width
        val fraction = if (width == 0) 1f else (constraints.maxWidth.toFloat() / (width + 2)).coerceAtMost(1f)
        Text(title, Modifier.fillMaxWidth().testTag(tag), style = style.copy(fontSize = (13f * fraction).sp),
            maxLines = 1, softWrap = false)
    }
}
