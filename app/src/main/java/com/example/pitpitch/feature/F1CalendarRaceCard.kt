package com.example.pitpitch.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.*
import com.example.pitpitch.data.reminder.ReminderBooking
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.ReminderBell
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun CalendarRaceRow(race: F1Race, zone: ZoneId, expanded: Boolean, open: () -> Unit) {
    val shape = RoundedCornerShape(if (expanded) 16.dp else 12.dp)
    Column(Modifier.fillMaxWidth().clip(shape)
        .then(if (expanded) Modifier.background(Color.White).border(1.dp, Line, shape) else Modifier)
        .testTag("race-${race.id}").clickable(onClick = open).padding(horizontal = 10.dp, vertical = 8.dp)) {
        // Identical heading geometry keeps the circuit aligned with completed weekends.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (expanded) "第 ${race.round} 站" else "第 ${race.round} 站 · ${shortRaceTime(race, zone)}",
                    color = if (expanded) RaceRed else Muted, fontSize = 11.sp)
                Text(homeRaceName(race.name), fontWeight = FontWeight.SemiBold, fontSize = if (expanded) 18.sp else 15.sp)
                Text(race.circuit, color = Muted, fontSize = if (expanded) 11.sp else 10.sp)
            }
            CircuitLayout(race, Modifier.width(76.dp).height(54.dp))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
            .testTag("f1-calendar-expanded-${race.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider(color = Line)
            CalendarSessionGrid(race, zone)
        }
    }
    if (!expanded) HorizontalDivider(Modifier.padding(horizontal = 10.dp).testTag("f1-race-divider-${race.id}"), color = Line)
}

private fun sessionTime(session: F1Session, zone: ZoneId): Pair<String, String> {
    val local = session.time?.let { runCatching { Instant.parse("${session.date}T$it").atZone(zone) }.getOrNull() }
    val date = local?.toLocalDate() ?: runCatching { LocalDate.parse(session.date) }.getOrNull()
    return (date?.format(DateTimeFormatter.ofPattern("MM月dd日 EEE", Locale.CHINA)) ?: "日期待定") to
        (local?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "时间待定")
}

@Composable private fun CalendarSessionGrid(race: F1Race, zone: ZoneId) {
    val sessions = remember(race) { race.displaySessions(includeRace = true) }
    val times = remember(sessions, zone) { sessions.associateWith { sessionTime(it, zone) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val cellWidth = (constraints.maxWidth / 3 - with(density) { 12.dp.roundToPx() }).coerceAtLeast(1)
        val bellWidth = with(density) { 48.dp.roundToPx() }
        val measurer = rememberTextMeasurer()
        val style = LocalTextStyle.current
        // Fit each text tier once for the entire grid so corresponding labels always share a size.
        fun fitted(values: List<Pair<String, Int>>, base: Float, weight: FontWeight): Float {
            var size = base
            repeat(6) {
                val ratio = values.minOf { (text, available) ->
                    val width = measurer.measure(text, style.copy(fontSize = size.sp, fontWeight = weight),
                        softWrap = false).multiParagraph.intrinsics.maxIntrinsicWidth
                    (available - 2).coerceAtLeast(1) / width.coerceAtLeast(1f)
                }
                if (ratio < 1f) size *= ratio * .97f
            }
            return size
        }
        val labelSize = fitted(sessions.map { it.displayTitle() to
            (cellWidth - if (it.title == "正赛") bellWidth else 0).coerceAtLeast(1) }, 13f, FontWeight.SemiBold)
        val dateSize = fitted(times.values.map { it.first to cellWidth }, 10f, FontWeight.Normal)
        val timeSize = fitted(times.values.map { it.second to cellWidth }, 22f, FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sessions.chunked(3).forEach { row ->
                val columns = List<F1Session?>(3 - row.size) { null } + row
                Row(Modifier.fillMaxWidth().drawBehind {
                    for (index in 1..2) {
                        val x = size.width * index / 3f
                        drawLine(Line, Offset(x, 8.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                    }
                }) {
                    columns.forEach { session ->
                        Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            if (session != null) CalendarSessionCell(race, session, times.getValue(session), labelSize, dateSize, timeSize)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CalendarSessionCell(race: F1Race, session: F1Session, time: Pair<String, String>,
    labelSize: Float, dateSize: Float, timeSize: Float) {
    val isRace = session.title == "正赛"
    val color = if (isRace) RaceRed else PrimaryText
    Column(Modifier.fillMaxWidth().testTag("f1-session-${race.id}-${session.title}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CalendarCellText(session.displayTitle(), labelSize, color, FontWeight.SemiBold)
            }
            if (isRace) Box(Modifier.width(48.dp).height(32.dp).wrapContentHeight(unbounded = true)) {
                ReminderBell(ReminderBooking(race.id, "F1 · ${homeRaceName(race.name)} · 正赛",
                    race.utcTime?.let { "${race.date}T$it" }, race.circuit, "Jolpica"), RaceRed)
            }
        }
        CalendarCellText(time.first, dateSize, Muted)
        CalendarCellText(time.second, timeSize, color, FontWeight.Bold)
    }
}

@Composable private fun CalendarCellText(value: String, size: Float, color: Color, weight: FontWeight = FontWeight.Normal) {
    Text(value, modifier = Modifier.fillMaxWidth(), color = color,
        style = LocalTextStyle.current.copy(fontSize = size.sp, lineHeight = (size * 1.4f).sp,
            fontWeight = weight, fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false)
}

private fun shortRaceTime(race: F1Race, zone: ZoneId): String = runCatching {
    if (race.utcTime == null) "${LocalDate.parse(race.date).format(DateTimeFormatter.ofPattern("MM.dd"))} 时间待定"
    else Instant.parse("${race.date}T${race.utcTime}").atZone(zone).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))
}.getOrDefault("时间待定")
