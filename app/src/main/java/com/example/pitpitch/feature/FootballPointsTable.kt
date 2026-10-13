package com.example.pitpitch.feature

import com.example.pitpitch.ui.components.footballTeamName
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.TeamBadge

private fun zoneColor(zone: TableZone?): Color = when (zone) {
    TableZone.CHAMPIONS, TableZone.ADVANCE -> Color(0xFF008A4B)
    TableZone.CHAMPIONS_QUALIFYING, TableZone.EUROPA -> Color(0xFF2979C8)
    TableZone.CONFERENCE -> Color(0xFF8065B3)
    TableZone.PLAYOFF, TableZone.RELEGATION_PLAYOFF -> Color(0xFFA46A00)
    TableZone.RELEGATION, TableZone.ELIMINATED -> Color(0xFFCF3A3A)
    null -> Color.Transparent
}

@Composable fun FootballPointsTable(table: FootballTable, league: String, year: Int, openTeam: (Int) -> Unit) {
    val marks = table.entries.map { standingMark(it, table, league, year) }
    Column(Modifier.fillMaxWidth().testTag("football-points-table-${table.name}")) {
        Row(Modifier.fillMaxWidth().background(PanelSurface).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", Modifier.width(24.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center)
            Text("球队", Modifier.weight(1f), fontSize = 11.sp, color = Muted)
            Text("场", Modifier.width(28.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center)
            Text("胜/平/负", Modifier.width(58.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center)
            Text("进/失", Modifier.width(44.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center)
            Text("积分", Modifier.width(34.dp), fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center)
        }
        table.entries.forEachIndexed { index, row ->
            val mark = marks[index]
            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("standing-${row.team.id}")
                .clickable { openTeam(row.team.id) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(28.dp).background(zoneColor(mark.zone)))
                Text(row.position, Modifier.width(21.dp), fontSize = 12.sp, textAlign = TextAlign.Center, color = PrimaryText)
                Row(Modifier.weight(1f).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TeamBadge(footballTeamName(row.team.id, row.team.name), row.team.id, row.team.badgeUrl, size = 18.dp)
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(footballTeamName(row.team.id, row.team.name), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!row.deductions.isNullOrBlank()) Text("扣分 ${row.deductions}", color = Muted, fontSize = 9.sp)
                    }
                    if (mark.confirmed) {
                        val relegated = mark.zone == TableZone.RELEGATION || mark.zone == TableZone.ELIMINATED
                        val icon = if (relegated) Icons.Outlined.ArrowDownward else if (mark.zone in listOf(TableZone.CHAMPIONS, TableZone.ADVANCE)) Icons.Outlined.SportsSoccer else Icons.Outlined.CheckCircle
                        val label = when (mark.zone) {
                            TableZone.CHAMPIONS -> "已确认欧冠席位"
                            TableZone.ADVANCE -> "已确认晋级"
                            TableZone.RELEGATION -> "已确认降级"
                            TableZone.ELIMINATED -> "已确认淘汰"
                            else -> "已确认${mark.zone?.label}"
                        }
                        Icon(icon, label, Modifier.size(14.dp), tint = zoneColor(mark.zone))
                    }
                }
                Text(row.played, Modifier.width(28.dp), fontSize = 11.sp, textAlign = TextAlign.Center)
                Text("${row.won}/${row.drawn}/${row.lost}", Modifier.width(58.dp), fontSize = 10.sp, textAlign = TextAlign.Center)
                Text("${row.goalsFor}/${row.goalsAgainst}", Modifier.width(44.dp), fontSize = 10.sp, textAlign = TextAlign.Center)
                Text(row.points, Modifier.width(34.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            HorizontalDivider(color = Line)
        }
        val zones = marks.mapNotNull { it.zone }.distinct()
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            zones.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { zone -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(3.dp).height(14.dp).background(zoneColor(zone)))
                    Text(zone.label, color = Muted, fontSize = 10.sp)
                } }
            } }
        }
    }
}
@Composable fun FootballRules(league: String, year: Int) {
    Column(Modifier.fillMaxWidth().testTag("football-table-rules").padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${footballCompetitions.firstOrNull { it.id == league }?.title ?: "赛事"}积分与排名规则 · $year/${year + 1}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        footballRulesText(league, year).forEach { Text(it, color = Muted, fontSize = 13.sp, lineHeight = 20.sp) }
    }
}
