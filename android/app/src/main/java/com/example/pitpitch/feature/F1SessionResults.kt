package com.example.pitpitch.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.RankingEntry
import com.example.pitpitch.data.repository.QualifyingEntry
import com.example.pitpitch.ui.*

private val retirementStatuses = setOf("Accident", "Brakes", "Collision", "Collision damage", "Cooling system", "Damage",
    "Debris", "Differential", "Electrical", "Electronics", "Engine", "Fuel leak", "Fuel pressure", "Fuel pump", "Gearbox",
    "Hydraulics", "Illness", "Mechanical", "Oil leak", "Overheating", "Power Unit", "Power loss", "Puncture",
    "Radiator", "Rear wing", "Retired", "Spun off", "Suspension", "Transmission", "Turbo", "Undertray", "Water leak",
    "Water pressure", "Water pump", "Wheel", "Wheel nut")

/** Compact display only; provider details, laps and times stay untouched in Room. */
fun raceResultLabel(entry: RankingEntry): String {
    val status = entry.detail.substringBefore(" · ")
    return when {
        status == "Lapped" -> entry.detail.split(" · ").getOrNull(2) ?: "被套圈"
        status == "Finished" -> entry.detail.split(" · ").getOrNull(2) ?: "完赛"
        status in retirementStatuses -> "退赛"
        status == "Disqualified" || status == "Excluded" -> "取消资格"
        status == "Did not start" -> "未发车"
        status == "Withdrew" -> "退出参赛"
        status == "Did not qualify" -> "未获资格"
        status == "Not classified" -> "未分类"
        status.matches(Regex("\\+\\d+ Laps?")) -> status.substringBefore(' ') + " 圈"
        status == "状态未提供" -> "—"
        else -> status
    }
}

@Composable fun F1SessionResultHeader(points: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("名次", Modifier.width(30.dp), color = Muted, fontSize = 11.sp)
        Spacer(Modifier.width(3.dp))
        Text("车手", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        Text("结果", Modifier.width(80.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.End)
        if (points) Text("积分", Modifier.width(44.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.End)
    }
}
@Composable private fun SessionResultRow(rank: String, name: String, result: String, points: String?, tag: String, accent: Color) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rank, Modifier.width(30.dp), fontSize = 14.sp)
            Box(Modifier.width(3.dp).height(24.dp).background(accent).testTag("$tag-team-color"))
            Text(name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(result, Modifier.width(80.dp), fontSize = 12.sp, textAlign = TextAlign.End)
            if (points != null) Text(points, Modifier.width(44.dp).testTag("$tag-points"),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = Line)
    }
}
@Composable fun QualifyingResultRow(entry: QualifyingEntry, season: Int) = SessionResultRow(entry.position ?: "—", entry.name,
    entry.q3 ?: entry.q2 ?: entry.q1 ?: "—", null, "f1-qualifying-${entry.driverId}", constructorColor(entry.constructorId, season))
@Composable fun RaceResultRow(entry: RankingEntry, season: Int) = SessionResultRow(entry.position, entry.name,
    raceResultLabel(entry), entry.points, "f1-race-result-$season-${entry.driverId ?: entry.name}", constructorColor(entry.constructorId, season))
