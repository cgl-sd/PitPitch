package com.example.pitpitch.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.reminder.ReminderBooking
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.*
import java.time.ZoneId
import java.time.Year

@Composable fun RaceCard(race: F1Race, zone: ZoneId, selected: Boolean?, toggle: () -> Unit, open: (() -> Unit)?) {
    Box(Modifier.testTag("race-${race.id}")) { Panel(open) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${race.season} · 第 ${race.round} 站", color = RaceRed, fontSize = 12.sp)
                Text(homeRaceName(race.name), fontWeight = FontWeight.Bold)
            }
            if (selected != null) FavoriteButton(selected, homeRaceName(race.name), toggle)
        }
        Text(race.localTime(zone), color = Muted)
        Text(f1CircuitName(race), color = Muted, fontSize = 12.sp)
    } }
}
@Composable fun RaceDetail(race: F1Race, zone: ZoneId, selected: Boolean?, toggle: () -> Unit, updated: String?, cached: Boolean, results: RemoteSnapshot<List<RankingEntry>>? = null, loading: Boolean = false, error: String? = null, retry: () -> Unit = {}, qualifying: RemoteSnapshot<List<com.example.pitpitch.data.repository.QualifyingEntry>>? = null, qualifyingLoading: Boolean = false, qualifyingError: String? = null) {
    var section by rememberSaveable(race.id) { mutableIntStateOf(0) }
    val entries = results?.data.orEmpty()
    key(section) {
    Page {
        item { CircuitLayout(race) }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountryFlag(race.country)
                Column(Modifier.weight(1f)) {
                    Text(homeRaceName(race.name), fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Text("${race.season} · 第 ${race.round} 站 · ${f1CircuitName(race)}", color = Muted, fontSize = 11.sp)
                }
            }
        }
        item { Segments(listOf("周末安排", "排位成绩", "正赛积分"), section, { section = it }, Modifier.testTag("f1-detail-section"), accent = RaceRed, itemTagPrefix = "f1-detail-section") }
        when (section) {
            0 -> {
                item { Heading("比赛周末", zoneLabel(zone)) }
                items(race.displaySessions(includeRace = true)) { session ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.displayTitle(), fontSize = 14.sp, color = if (session.title == "正赛") RaceRed else PrimaryText)
                            Text(session.localTime(zone), color = Muted, fontSize = 12.sp)
                        }
                        ReminderBell(ReminderBooking(if (session.title == "正赛") race.id else "${race.id}-${session.title}", "F1 · ${homeRaceName(race.name)} · ${session.displayTitle()}", session.time?.let { "${session.date}T$it" }, f1CircuitName(race), "Jolpica"), RaceRed)
                    }
                    HorizontalDivider(color = Line)
                }
            }
            1 -> {
                item { Heading("排位赛成绩") }
                val failure = qualifying?.error ?: qualifyingError
                if (failure != null) item { StatePanel("排位成绩更新未成功", failure, retry = retry) }
                if (!qualifyingLoading && failure == null && qualifying?.data.orEmpty().isEmpty()) item {
                    StatePanel("暂无排位成绩", "该场排位成绩尚未发布或来源未提供。", retry = retry)
                }
                if (qualifying?.data.orEmpty().isNotEmpty()) {
                    item { F1SessionResultHeader(points = false) }
                    items(qualifying?.data.orEmpty(), key = { it.driverId }) { entry ->
                        QualifyingResultRow(entry, race.season)
                    }
                }
            }
            2 -> {
                item { Heading("正赛积分") }
                val failure = results?.error ?: error
                if (failure != null) item { StatePanel("成绩读取未成功", failure, retry = retry) }
                if (entries.isEmpty()) {
                    if (!loading && failure == null) item { StatePanel("暂无正赛成绩", "比赛尚未举行或归档尚未发布结果。", retry = retry) }
                } else {
                    item { F1SessionResultHeader(points = true) }
                    items(entries, key = { it.driverId ?: it.name }) { RaceResultRow(it, race.season) }
                }
            }
        }
    }
    }
}
@Composable fun StandingsScreen(state: SportsViewModel.State, zone: ZoneId, retry: () -> Unit) {
    var type by rememberSaveable { mutableIntStateOf(0) }
    val response = if (type == 0) state.drivers else state.constructors
    Page {
        item { Heading("${state.season} 赛季积分", "年度累计积分") }
        item { F1SeasonProgressBar(state.season, state.races, response?.data?.round) }
        item { Segments(listOf("车手", "车队"), type, { type = it }, accent = RaceRed) }
        val failure = response?.error ?: state.rankingError
        item { RemoteStatus(response?.fetchedUtc, response?.cached == true, failure, state.rankingLoading, zone, retry) }
        if (!state.rankingLoading && failure == null && response?.data?.entries.orEmpty().isEmpty()) item { StatePanel("暂无积分", "该赛季尚无已发布积分，不生成示例排名。") }
        items(response?.data?.entries.orEmpty(), key = { it.name }) { F1StandingRow(it, season = state.season, constructors = type == 1) }
    }
}
@Composable fun RemoteStatus(updated: String?, cached: Boolean, error: String?, loading: Boolean, zone: ZoneId, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error != null) StatePanel("数据读取失败", error, retry = retry)
    }
}
@Composable fun RankingCard(entry: RankingEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.position, Modifier.widthIn(min = 32.dp), color = FootballGreen, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) { Text(entry.name, fontWeight = FontWeight.SemiBold); Text(f1ConstructorDisplayName(entry.team), color = Muted, fontSize = 12.sp) }
            Text("${entry.points} 分", fontWeight = FontWeight.Bold)
        }
        Text(entry.detail, color = Muted, fontSize = 12.sp)
        HorizontalDivider(color = Line)
    }
}
