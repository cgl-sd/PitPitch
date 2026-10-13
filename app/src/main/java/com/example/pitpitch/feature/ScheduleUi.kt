package com.example.pitpitch.feature

import com.example.pitpitch.ui.components.footballTeamName
import com.example.pitpitch.ui.components.footballVenueName
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import com.example.pitpitch.data.reminder.ReminderBooking
import com.example.pitpitch.ui.components.ReminderBell
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.TeamBadge
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ScheduleIndicatorGap = 7.dp
private val ScheduleIndicatorHeight = 3.dp

@Composable fun ScheduleTabs(labels: List<String>, current: Int, tag: String, select: (Int) -> Unit, accent: Color = FootballAccent) {
    val largeText = LocalDensity.current.fontScale > 1.2f
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).testTag("$tag-$index")
                .semantics { selected = index == current; role = Role.Tab }
                .clip(RoundedCornerShape(8.dp))
                .clickable { select(index) }.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ScheduleIndicatorGap)) {
                    val displayLabel = if (largeText && label.length == 4) label.take(2) + "\n" + label.takeLast(2) else label
                    Text(displayLabel, color = if (current == index) accent else Muted, fontSize = 14.sp,
                        fontWeight = if (current == index) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                    Box(Modifier.width(22.dp).height(ScheduleIndicatorHeight).background(if (current == index) accent else Color.Transparent, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable fun ScheduleMenu(label: String, tag: String, options: List<Pair<String, String>>, select: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton({ expanded = true }, Modifier.testTag(tag).heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = PrimaryText), shape = RoundedCornerShape(8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$label ▾", style = MaterialTheme.typography.bodyLarge, fontSize = 14.sp, fontWeight = FontWeight.Normal)
                Spacer(Modifier.height(ScheduleIndicatorGap + ScheduleIndicatorHeight))
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { select(key); expanded = false }, modifier = Modifier.testTag("$tag-option-$key")) }
        }
    }
}

@Composable fun FootballFixtureRow(match: FootballMatch, zone: ZoneId, showDivider: Boolean = true, open: () -> Unit) {
    val start = match.kickoffUtc?.let { runCatching { Instant.parse(it).atZone(zone) }.getOrNull() }
    val scheduled = match.status.contains("待赛")
    Column(Modifier.fillMaxWidth().background(Color.White)
        .testTag("match-${match.id}").clickable(onClick = open).padding(horizontal = 0.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(start?.format(DateTimeFormatter.ofPattern("MM-dd EEE HH:mm", Locale.CHINA)) ?: "${match.date} · 时间待定",
                Modifier.weight(1f), color = Muted, fontSize = 12.sp)
            if (!scheduled) Text(match.status, color = Muted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FixtureTeamName(footballTeamName(match.homeTeamId, match.homeShort), Modifier.weight(1f), "fixture-home-name-${match.id}", TextAlign.End)
                TeamBadge(footballTeamName(match.homeTeamId, match.home), match.homeTeamId, match.homeBadgeUrl, size = 24.dp)
            }
            Box(Modifier.width(68.dp), contentAlignment = Alignment.Center) {
                if (scheduled) ReminderBell(ReminderBooking(match.id, "${footballTeamName(match.homeTeamId, match.home)} 对 ${footballTeamName(match.awayTeamId, match.away)}", match.kickoffUtc, footballVenueName(match.stadium), match.source))
                else Text(match.score, fontSize = 18.sp, color = if (match.status.contains("完场")) FootballGreen else PrimaryText,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamBadge(footballTeamName(match.awayTeamId, match.away), match.awayTeamId, match.awayBadgeUrl, size = 24.dp)
                FixtureTeamName(footballTeamName(match.awayTeamId, match.awayShort), Modifier.weight(1f), "fixture-away-name-${match.id}", TextAlign.Start)
            }
        }
        if (showDivider) HorizontalDivider(color = Line)
    }
}

/** Use the reviewed club name in full. Narrow screens/font scaling never erase part of the identity. */
@Composable private fun FixtureTeamName(name: String, modifier: Modifier, tag: String, alignment: TextAlign) {
    BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val style = LocalTextStyle.current.copy(fontSize = 14.sp, letterSpacing = 0.sp)
        val width = measurer.measure(name, style, softWrap = false).size.width
        val fraction = if (width <= 0) 1f else (constraints.maxWidth.toFloat() / (width + 2)).coerceAtMost(1f)
        Text(name, Modifier.fillMaxWidth().testTag(tag), style = style.copy(fontSize = (14f * fraction).sp),
            textAlign = alignment, maxLines = 1, softWrap = false)
    }
}

@Composable fun StandingHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#", Modifier.width(24.dp), color = Muted, fontSize = 11.sp)
        Text("球队", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        listOf("场", "进/失", "净", "分").forEachIndexed { index, text -> Text(text, Modifier.width(if (index == 1) 46.dp else 28.dp), color = Muted, fontSize = 11.sp) }
    }
}
@Composable fun StandingRow(row: com.example.pitpitch.data.FootballStanding) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.position, Modifier.width(24.dp), fontSize = 12.sp)
            TeamBadge(footballTeamName(row.team.id, row.team.name), row.team.id, row.team.badgeUrl, size = 20.dp)
            Text(footballTeamName(row.team.id, row.team.name), Modifier.weight(1f), fontSize = 12.sp)
            Text(row.played, Modifier.width(28.dp), fontSize = 12.sp)
            Text("${row.goalsFor}/${row.goalsAgainst}", Modifier.width(46.dp), fontSize = 11.sp)
            Text(row.difference, Modifier.width(28.dp), fontSize = 12.sp)
            Text(row.points, Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Text("胜 ${row.won} · 平 ${row.drawn} · 负 ${row.lost}${row.deductions?.let { " · 扣分 $it" } ?: ""}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 46.dp))
        HorizontalDivider(color = Line, modifier = Modifier.padding(top = 8.dp))
    }
}
@Composable fun PlayerRow(player: com.example.pitpitch.data.FootballPlayer) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(player.name, fontSize = 14.sp); Text("${player.jersey?.let { "#$it · " } ?: ""}${player.position}", color = Muted, fontSize = 12.sp) }
            listOf(player.goals, player.appearances, player.assists).forEach { Text(it ?: "—", Modifier.width(34.dp), fontSize = 13.sp) }
        }
        HorizontalDivider(color = Line, modifier = Modifier.padding(top = 8.dp))
    }
}
