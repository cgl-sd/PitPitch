package com.example.pitpitch.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.FootballMatch
import com.example.pitpitch.data.MatchStatistic
import com.example.pitpitch.ui.Muted
import com.example.pitpitch.ui.components.TeamBadge
import com.example.pitpitch.ui.components.footballTeamName

private val StatisticColor = Color(0xFF1685D8)

@Composable internal fun MatchStatistics(match: FootballMatch, statistics: List<MatchStatistic>) {
    val homeColor = StatisticColor
    val awayColor = StatisticColor
    Surface(Modifier.fillMaxWidth().testTag("match-statistics"), color = Color.White,
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatisticTeam(match.homeTeamId, match.home, match.homeBadgeUrl, Modifier.weight(1f))
                StatisticTeam(match.awayTeamId, match.away, match.awayBadgeUrl, Modifier.weight(1f))
            }
            statistics.forEach { statistic ->
                Column(Modifier.fillMaxWidth().testTag("statistic-${statistic.label}").padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(statistic.home ?: "—", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(statistic.label, Modifier.weight(2f), textAlign = TextAlign.Center, color = Muted, fontSize = 13.sp)
                        Text(statistic.away ?: "—", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val home = statistic.home?.trim()?.removeSuffix("%")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    val away = statistic.away?.trim()?.removeSuffix("%")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    if (home != null && away != null) {
                        val max = maxOf(home, away)
                        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            StatisticBar(if (max > 0) (home / max).toFloat() else 0f, homeColor, true, Modifier.weight(1f))
                            StatisticBar(if (max > 0) (away / max).toFloat() else 0f, awayColor, false, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun StatisticTeam(id: Int?, original: String, url: String?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamBadge(footballTeamName(id, original), id, url, size = 24.dp)
        Text(footballTeamName(id, original), modifier = Modifier.weight(1f), fontSize = 12.sp, color = Muted)
    }
}

@Composable private fun StatisticBar(fraction: Float, color: Color, home: Boolean, modifier: Modifier) {
    Box(modifier.height(5.dp),
        contentAlignment = if (home) Alignment.CenterEnd else Alignment.CenterStart) {
        if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(3.dp)).background(color))
    }
}
