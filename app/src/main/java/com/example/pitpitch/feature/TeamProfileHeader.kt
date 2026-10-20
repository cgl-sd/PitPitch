package com.example.pitpitch.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.localBackend
import com.example.pitpitch.ui.components.*

@Composable internal fun TeamProfileHeader(team: FootballTeam, year: Int, league: String,
    standing: FootballStanding?, venue: String?, rankingScope: String? = null) {
    val references by localBackend(LocalContext.current).references.collectAsStateWithLifecycle()
    val hex = references.teamColors.optJSONObject(team.id.toString())?.optString("color")
    val primary = hex?.takeIf { Regex("[0-9A-Fa-f]{6}").matches(it) }?.let { Color(0xFF000000L or it.toLong(16)) } ?: Color(0xFF475569)
    val foreground = if (primary.luminance() > .45f) Color.Black else Color.White
    val secondary = foreground.copy(alpha = .8f)
    val leagueTitle = footballCompetitions.find { it.id == league }?.title ?: "球队"
    val title = footballTeamName(team.id, team.name)
    Surface(Modifier.fillMaxWidth().testTag("team-profile"), shape = RoundedCornerShape(16.dp), color = primary) {
        Box(Modifier.background(Brush.verticalGradient(listOf(primary, lerp(primary, Color.Black, .16f))))) {
            TeamBadge(title, team.id, team.badgeUrl, modifier = Modifier.matchParentSize()
                .clearAndSetSemantics { }.graphicsLayer { alpha = .14f; compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = .65f), Color.Transparent)), blendMode = BlendMode.DstIn)
                })
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = foreground, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                    if (title != team.name) Text(team.name, color = secondary, fontSize = 13.sp, lineHeight = 18.sp)
                    Text("$year/${year + 1} 赛季 · $leagueTitle", color = secondary, fontSize = 12.sp, lineHeight = 16.sp)
                    if (venue != null) Text(footballVenueName(venue), color = secondary, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.testTag("team-profile-venue"))
                }
                Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TeamBadge(title, team.id, team.badgeUrl, size = 64.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(standing?.position?.takeIf { it.isNotBlank() } ?: "—", color = foreground, fontSize = 22.sp, lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("team-profile-position"))
                        Text("${rankingScope ?: leagueTitle}排名", Modifier.weight(1f), color = secondary, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
