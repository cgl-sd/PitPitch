package com.example.pitpitch.feature

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.CompetitionLogo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.*

private sealed interface HomeCard {
    data class Football(val round: HomeFootballRound) : HomeCard
    data class Race(val race: F1Race) : HomeCard
}

@Composable fun HomeScreen(state: SportsViewModel.State, zone: ZoneId, openMatch: (String) -> Unit,
    openRace: (String) -> Unit, football: () -> Unit, f1: () -> Unit, settings: () -> Unit,
    openSchedule: (String) -> Unit = {}) {
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.matches, state.races, zone) {
        runHomeClock(lifecycle, state.matches, state.races, zone, onTick = { now = it })
    }
    val enabledFootball = footballCompetitions.any { it.id !in state.hiddenHomeCompetitions }
    val enabledF1 = "f1" !in state.hiddenHomeCompetitions
    // The whole season is ~2100 matches; keep the derived rounds and ordering off every recomposition.
    val rounds = remember(state.matches, state.hiddenHomeCompetitions, zone, now) {
        homeFootballRounds(state.matches.filter { it.competitionId !in state.hiddenHomeCompetitions }, zone, now)
    }
    val race = remember(state.races, zone, now) { homeRace(state.races, zone, now) }
    val cards = remember(rounds, race, enabledF1, zone, now) {
        buildList<HomeCard> {
            rounds.forEach { add(HomeCard.Football(it)) }
            if (enabledF1 && race != null) add(HomeCard.Race(race))
        }.sortedBy {
            when (it) {
                is HomeCard.Football -> homeMatchTime(it.round.matches.first(), zone)
                is HomeCard.Race -> homeRaceTime(it.race, zone, now)
            }
        }
    }
    Page(spacing = 10.dp) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("赛事赛程", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (state.homeReady) {
        if (state.ready && !state.footballLoading && enabledFootball && rounds.isEmpty()) item { StatePanel("暂无后续足球轮次", state.footballError ?: "暂无可用赛程，可在足球页查看其他赛季。", state.footballLoading, football) }
        cards.forEach { card -> when (card) {
        is HomeCard.Race -> item(key = card.race.id) { RaceWeekendCard(card.race, zone, homePreview = true) { openRace(card.race.id) } }
        is HomeCard.Football -> { val round = card.round; item(key = round.key) {
            // Tapping the card outside a fixture opens that competition's full schedule in the football page.
            HomeFootballRoundCard(round, zone, openMatch) { round.matches.firstOrNull()?.competitionId?.let(openSchedule) }
        } }
        } }
        if (!enabledFootball && !enabledF1) item { Panel {
            Text("尚未选择首页赛事", fontWeight = FontWeight.SemiBold)
            TextButton(settings) { Text("选择首页赛事") }
        } }
        if (state.ready && !state.loading && enabledF1 && race == null) item { StatePanel("暂无 F1 后续赛程", state.error ?: "可在 F1 页面查看全年赛程或其他赛季。", state.loading, f1) }
        }
    }
}

internal suspend fun runHomeClock(lifecycle: Lifecycle, matches: List<FootballMatch>, races: List<F1Race>, zone: ZoneId,
    onTick: (Instant) -> Unit, clock: () -> Instant = { Instant.now() }, wait: suspend (Long) -> Unit = { delay(it) }) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            val now = clock()
            onTick(now)
            val nextKickoff = (matches.mapNotNull { it.kickoffUtc?.let { utc -> runCatching { Instant.parse(utc) }.getOrNull() } } +
                races.flatMap { race -> race.displaySessions(includeRace = true, homePreview = true).filter { it.time != null }.map { homeSessionTime(it, zone) } })
                .filter { it != Instant.MAX && it.isAfter(now) }.minOrNull()
            val delayMillis = nextKickoff?.let { Duration.between(now, it).toMillis().coerceIn(0, 59_999) + 1 } ?: 60_000
            wait(delayMillis)
        }
    }
}

/** Equal match insets; only adjacent rows have separators, the card closes the last row. */
@Composable internal fun HomeFootballRoundCard(round: HomeFootballRound, zone: ZoneId, openMatch: (String) -> Unit, openSchedule: () -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("home-round-${round.key}").clickable(onClick = openSchedule), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Line), colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompetitionLogo(round.matches.firstOrNull()?.competitionId, round.competition)
                Text(round.competition, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                round.matches.firstOrNull()?.roundNumber?.let { Text("第 $it 轮", color = Muted, fontSize = 12.sp) }
            }
            if (round.matches.firstOrNull()?.roundNumber == null && !round.stage.contains("轮次未提供"))
                Text(round.stage, color = Muted, fontSize = 11.sp)
            val matches = round.matches.take(3)
            matches.forEachIndexed { index, match ->
                FootballFixtureRow(match, zone, showDivider = false) { openMatch(match.id) }
                if (index < matches.lastIndex) HorizontalDivider(Modifier.testTag("home-divider-${match.id}"), color = Line)
            }
        }
    }
}
