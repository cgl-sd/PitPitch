package com.example.pitpitch.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.reminder.ReminderBooking
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.ReminderBell
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

@Composable fun F1Screen(state: SportsViewModel.State, zone: ZoneId, open: (String) -> Unit,
    refresh: () -> Unit, season: (Int) -> Unit, rankings: () -> Unit, onSectionChanged: (Boolean) -> Unit = {}, refreshRankings: () -> Unit = rankings,
    ) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    // Retiring list content must keep its section snapshot, not rebuild as another
    // section and clamp the outgoing calendar's saved scroll position.
    val selectedSection = section
    LaunchedEffect(selectedSection, state.season) { onSectionChanged(selectedSection > 0); if (selectedSection > 0) rankings() }
    val races = remember(state.races) { state.races.sortedBy { it.round } }
    val expandedRace = f1UpcomingRace(races, Instant.now(), zone,
        state.results.filterValues { it.data.isNotEmpty() }.keys)
    val featured = expandedRace ?: races.firstOrNull()
    val calendarPositions = rememberSaveableStateHolder()
    val standingsState = rememberLazyListState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    var standingsContext by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedSection, state.season) {
        val context = "${state.season}|$selectedSection"
        if (selectedSection > 0 && standingsContext != context) {
            standingsState.scrollToItem(0)
            standingsContext = context
        }
    }
    calendarPositions.SaveableStateProvider(state.season) {
    val calendarState = rememberLazyListState()
    var calendarFocused by rememberSaveable { mutableStateOf(false) }
    val positioningCalendar = selectedSection == 0 && races.isNotEmpty() && !calendarFocused
    LaunchedEffect(selectedSection, state.season, races, featured?.id, state.loading, state.error != null, viewportHeight) {
        if (positioningCalendar && viewportHeight > 0) {
            val raceIndex = races.indexOfFirst { it.id == featured?.id }.coerceAtLeast(0)
            val leadingItems = 1 + if (state.error != null) 1 else 0
            // There is no trailing footer any more: the list is the heading item plus one row per race.
            val layout = snapshotFlow { calendarState.layoutInfo }.first {
                it.totalItemsCount == leadingItems + races.size && it.viewportSize.height > 0
            }
            when (raceIndex) {
                0 -> calendarState.scrollToItem(0)
                races.lastIndex -> calendarState.scrollToItem(layout.totalItemsCount - 1)
                else -> {
                    val itemIndex = leadingItems + raceIndex
                    calendarState.scrollToItem(itemIndex)
                    val measured = snapshotFlow { calendarState.layoutInfo }.first { info ->
                        info.visibleItemsInfo.any { it.key == featured?.id && it.size > 0 }
                    }
                    val row = measured.visibleItemsInfo.first { it.key == featured?.id }
                    // Item offsets exclude the leading content padding. An oversized weekend
                    // stays top-aligned so its heading is readable rather than clipped above.
                    val usableHeight = measured.viewportEndOffset - measured.afterContentPadding
                    val inset = ((usableHeight - row.size) / 2).coerceAtLeast(0)
                    calendarState.scrollToItem(itemIndex, -inset)
                }
            }
            calendarFocused = true
        }
    }
    val response = if (selectedSection == 2) state.constructors else state.drivers
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().testTag("f1-controls"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).alignByBaseline()) { ScheduleTabs(listOf("赛程", "车手积分", "车队积分"), selectedSection, "f1-section", { section = it }, RaceRed) }
            ScheduleMenu(state.season.toString(), "f1-season", f1ArchiveYears().map { it.toString() to "$it 赛季" }, { season(it.toInt()) }, Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).testTag("f1-list-viewport")
            .onSizeChanged { viewportHeight = it.height }
            .drawWithContent {
                if (!positioningCalendar) clipRect { this@drawWithContent.drawContent() }
            }) {
        key(state.season, selectedSection) {
        Page(state = if (selectedSection == 0) calendarState else standingsState, padding = PaddingValues(vertical = 8.dp)) {
        if (selectedSection == 0) {
            item { Text("全年赛程", Modifier.testTag("f1-schedule-title"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            if (state.error != null) item { StatePanel("更新失败", state.error, retry = refresh) }
            if (!state.loading && races.isEmpty() && state.error == null) item { StatePanel("暂无赛程", "该赛季暂无分站日程。", retry = refresh) }
            items(races, key = { it.id }) { race -> CalendarRaceRow(race, zone, race.id == expandedRace?.id) { open(race.id) } }
        } else {
            item { F1SeasonProgressBar(state.season, state.races, response?.data?.round) }
            if ((response?.error ?: state.rankingError) != null) item { StatePanel("积分更新未成功", response?.error ?: state.rankingError, retry = refreshRankings) }
            item { F1TableHeader(constructors = selectedSection == 2) }
            if (!state.rankingLoading && response?.data?.entries.orEmpty().isEmpty()) item { StatePanel("暂无积分", "该赛季尚无已发布积分。") }
            items(response?.data?.entries.orEmpty(), key = { it.name }) { F1StandingRow(it, season = state.season, constructors = selectedSection == 2) }
        }
        }
        }
        }
    }
    }
}

@Composable fun RaceWeekendCard(race: F1Race, zone: ZoneId, homePreview: Boolean = false, open: () -> Unit) {
    val sessions = race.displaySessions(homePreview = homePreview)
    Surface(Modifier.fillMaxWidth().testTag("f1-weekend"), color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column {
            Box(Modifier.fillMaxWidth().height(3.dp).background(RaceRed))
            if (homePreview) CircuitPhoto(race, Modifier.fillMaxWidth().height(140.dp))
            else CircuitLayout(race, Modifier.fillMaxWidth().height(180.dp))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CountryFlag(race.country)
                    Column(Modifier.weight(1f)) {
                        Text(homeRaceName(race.name), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(race.circuit, color = Muted, fontSize = 11.sp)
                    }
                    Text("第 ${race.round} 站", color = Muted, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(race.localTime(zone), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("正赛 · ${zoneLabel(zone)}", color = Muted, fontSize = 11.sp)
                    }
                    ReminderBell(ReminderBooking(race.id, "F1 · ${homeRaceName(race.name)} · 正赛", race.utcTime?.let { "${race.date}T$it" }, race.circuit, "Jolpica"), RaceRed)
                }
                HorizontalDivider(color = Line)
                sessions.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { session -> Column(Modifier.weight(1f)) {
                            Text(session.displayTitle(), fontSize = 12.sp)
                            Text(session.localTime(zone), color = Muted, fontSize = 11.sp)
                        } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (!homePreview) TextButton(open, Modifier.testTag("f1-weekend-detail").fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = RaceRed)) { Text("查看分站详情 ›", fontSize = 12.sp) }
            }
        }
    }
}

@Composable private fun CalendarRaceRow(race: F1Race, zone: ZoneId, expanded: Boolean, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("race-${race.id}").clickable(onClick = open)) {
        Row(Modifier.heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(homeRaceName(race.name), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("第 ${race.round} 站", color = Muted, fontSize = 11.sp)
                }
                Text(race.circuit, color = Muted, fontSize = 10.sp)
                Text(shortRaceTime(race, zone).replace('\n', ' '), color = Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            CircuitLayout(race, Modifier.width(96.dp).height(64.dp))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 12.dp)
            .testTag("f1-calendar-expanded-${race.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            race.displaySessions(includeRace = true).forEach { session ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.displayTitle(), fontSize = 12.sp)
                        Text(session.localTime(zone), color = Muted, fontSize = 11.sp)
                    }
                    ReminderBell(ReminderBooking(if (session.title == "正赛") race.id else "${race.id}-${session.title}", "F1 · ${homeRaceName(race.name)} · ${session.displayTitle()}",
                        session.time?.let { "${session.date}T$it" }, race.circuit, "Jolpica"), RaceRed)
                }
            }
        }
        HorizontalDivider(color = Line)
    }
}
private fun shortRaceTime(race: F1Race, zone: ZoneId): String = runCatching {
    if (race.utcTime == null) "${LocalDate.parse(race.date).format(DateTimeFormatter.ofPattern("MM.dd"))}\n时间待定"
    else Instant.parse("${race.date}T${race.utcTime}").atZone(zone).format(DateTimeFormatter.ofPattern("MM.dd\nHH:mm"))
}.getOrDefault("时间待定")
fun zoneLabel(zone: ZoneId): String = if (zone.id == "Asia/Shanghai") "北京时间" else zone.id
