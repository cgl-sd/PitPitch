package com.example.pitpitch.feature


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pitpitch.data.FootballPlayer
import com.example.pitpitch.data.repository.localBackend

/** A vector diagram using only reconciled published formation rows. Attack points upward. */
@Composable fun LineupPitch(rows: List<List<FootballPlayer>>, teamId: Int? = null) {
    val references by localBackend(LocalContext.current).references.collectAsStateWithLifecycle()
    val hex = references.teamColors.optJSONObject(teamId.toString())?.optString("color")
    val shirtColor = hex?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }
        ?.let { Color(0xFF000000L or it.toLong(16)) } ?: Color(0xFFE5E7EB)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val height = maxOf(500f, rows.size * 112f * scale).dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF278249)).testTag("lineup-pitch")) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val white = Color.White.copy(alpha = .65f)
                val stroke = Stroke(1.5.dp.toPx())
                drawRect(white, style = stroke)
                drawLine(white, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke.width)
                drawCircle(white, size.width * .14f, center, style = stroke)
                drawCircle(white, 2.dp.toPx(), center)
                val area = Size(size.width * .62f, size.height * .15f)
                val small = Size(size.width * .3f, size.height * .06f)
                for (bottom in listOf(false, true)) {
                    drawRect(white, Offset(size.width * .19f, if (bottom) size.height - area.height else 0f), area, style = stroke)
                    drawRect(white, Offset(size.width * .35f, if (bottom) size.height - small.height else 0f), small, style = stroke)
                    drawCircle(white, 2.dp.toPx(), Offset(size.width / 2, size.height * if (bottom) .9f else .1f))
                }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 20.dp), verticalArrangement = Arrangement.SpaceAround) {
                rows.asReversed().forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { player ->
                            Column(Modifier.weight(1f).padding(horizontal = 2.dp).testTag("pitch-player-${player.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
                                PlayerShirt(player, shirtColor)
                                Text(player.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PlayerShirt(player: FootballPlayer, shirtColor: Color) {
    val numberColor = if (shirtColor.luminance() > .45f) Color(0xFF17212D) else Color.White
    Box(Modifier.size(44.dp, 42.dp).testTag("pitch-shirt-${player.id}"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val shirt = Path().apply {
                moveTo(w * .3f, h * .05f); lineTo(w * .12f, h * .13f); lineTo(0f, h * .38f)
                lineTo(w * .18f, h * .49f); lineTo(w * .24f, h * .36f); lineTo(w * .24f, h * .96f)
                lineTo(w * .76f, h * .96f); lineTo(w * .76f, h * .36f); lineTo(w * .82f, h * .49f)
                lineTo(w, h * .38f); lineTo(w * .88f, h * .13f); lineTo(w * .7f, h * .05f)
                quadraticTo(w * .5f, h * .24f, w * .3f, h * .05f); close()
            }
            drawPath(shirt, shirtColor)
            drawPath(shirt, numberColor.copy(alpha = .5f), style = Stroke(1.dp.toPx()))
        }
        Text(player.jersey ?: "—", color = numberColor, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.padding(top = 5.dp))
    }
}
