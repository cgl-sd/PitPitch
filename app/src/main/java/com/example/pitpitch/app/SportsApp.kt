package com.example.pitpitch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pitpitch.feature.*
import com.example.pitpitch.ui.*
import com.example.pitpitch.ui.components.*
import java.time.ZoneId
import com.example.pitpitch.data.currentFootballYear

/** Keep every UTC→local label on the device zone even when it changes while the app is in front. */
@Composable private fun rememberSystemZone(): ZoneId {
    val context = LocalContext.current
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { zone = ZoneId.systemDefault() }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        }.isSuccess
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
    return zone
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SportsApp(model: SportsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val detail = entry?.destination?.route != null && entry?.destination?.route != "main"
    val holder = rememberSaveableStateHolder()
    val zone = rememberSystemZone()
    val homeTaps = remember { com.example.pitpitch.data.HomeRefreshTaps(android.view.ViewConfiguration.getDoubleTapTimeout().toLong()) }
    val tabs = listOf(NavItem("首页", Icons.Outlined.Home), NavItem("足球", Icons.Outlined.SportsSoccer), NavItem("F1", Icons.Outlined.SportsScore))
    LaunchedEffect(Unit) { if (tab !in tabs.indices) tab = 0 }
    // One background refresh after the first local frame; the bottom bar refreshes the same feeds on demand.
    LaunchedEffect(state.homeReady) { if (state.homeReady) model.refreshOnStartupIfNeeded() }
    // Bumped when the home feed asks for a competition schedule, so the football page shows its schedule tab.
    var scheduleRequest by rememberSaveable { mutableIntStateOf(0) }
    fun openMatch(id: String, year: Int = state.footballSeason) { nav.navigate("match/$year/$id") { launchSingleTop = true } }
    fun openRace(id: String) { nav.navigate("race/$id") { launchSingleTop = true } }
    fun openTeam(id: Int, preferredLeague: String? = null, year: Int = state.footballSeason) {
        nav.navigate("team/$year/${preferredLeague ?: "auto"}/$id") { launchSingleTop = true }
    }
    MaterialTheme(colorScheme = SportsColors) {
        ReminderNoticeHost {
        Scaffold(containerColor = Background,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).testTag("main-topbar").padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (detail) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级")
                    }
                    if ((!detail && tab == 2) || entry?.destination?.route == "race/{id}") F1Brand()
                    else if (!detail && tab == 1) FootballBrand()
                    else if (!detail) PitPitchWordmark()
                    else Text(when (entry?.destination?.route) {
                        "team/{year}/{league}/{id}" -> "球队详情"; "match/{year}/{id}" -> "比赛详情"; "settings" -> "设置"; "about" -> "隐私和关于"; "teams" -> "选择主队"; else -> "F1 积分与成绩"
                    }, fontSize = 19.sp, color = PrimaryText)
                    Spacer(Modifier.weight(1f))
                    if (!detail && tab == 0) IconButton(onClick = { nav.navigate("settings") { launchSingleTop = true } },
                        modifier = Modifier.testTag("open-settings")) { Icon(Icons.Outlined.Settings, "设置") }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (state.storageError != null) Text(state.storageError!!, color = RaceRed)
                NavHost(navController = nav, startDestination = "main", modifier = Modifier.weight(1f)) {
                    composable("main") {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).testTag("main-page-viewport")) {
                                holder.SaveableStateProvider("tab-$tab") {
                                    when (tab) {
                                        0 -> HomeScreen(state, zone, { openMatch(it, currentFootballYear()) }, ::openRace, { tab = 1 }, { tab = 2 }, { nav.navigate("settings") { launchSingleTop = true } },
                                           openSchedule = { competitionId -> tab = 1; scheduleRequest++; model.footballSeason(currentFootballYear()); model.footballCompetition(competitionId) },
                                           onRefresh = { model.refreshHome() })
                                        1 -> {
                                            LaunchedEffect(state.footballCompetition, state.footballSeason) { model.refreshDayOverlay(); model.startLivePolling() }
                                            FootballScreen(state, zone, { openMatch(it) }, model::refreshFootball, model::footballSeason, model::footballCompetition, { openTeam(it, state.footballCompetition) }, model::loadLeaderboards, model::refreshFootballStandings,
                                                scheduleRequest = scheduleRequest)
                                        }
                                        2 -> F1Screen(state, zone, ::openRace, model::refresh, model::season, model::rankings, {}, model::refreshRankings)
                                    }
                                }
                            }
                            BottomBar(tabs, tab, { index ->
                                if (index == 0) {
                                    tab = 0
                                    if (homeTaps.tap(android.os.SystemClock.uptimeMillis())) model.refreshHome()
                                } else {
                                    homeTaps.reset()
                                    if (index == tab) model.refreshAll() else tab = index
                                }
                            }, state.homeTeam, {
                                val team = state.homeTeam
                                if (team == null) nav.navigate("teams") { launchSingleTop = true }
                                else openTeam(team.id)
                            }, onHomeRefresh = { model.refreshHome() })
                        }
                    }
                    composable("match/{year}/{id}", arguments = listOf(navArgument("year") { type = NavType.IntType }, navArgument("id") { type = NavType.StringType })) { back ->
                        val year = back.arguments!!.getInt("year")
                        LaunchedEffect(year) { model.loadFootballRoute(year) }
                        val route = state.footballSeasons[year]
                        val id = back.arguments?.getString("id")
                        val matches = if (year == state.footballSeason) state.matches else route?.matches.orEmpty()
                        val match = matches.find { it.id == id }
                            ?: state.homeMatches?.takeIf { year == currentFootballYear() }?.find { it.id == id }
                        if (match == null) {
                            val routeError = state.routeErrors["football:$year"]
                            when {
                                routeError != null -> StatePanel("赛事不可用", routeError)
                                route == null -> Box(Modifier.fillMaxSize())
                                else -> StatePanel("赛事不可用", "当前数据模式不提供此赛事。")
                            }
                        } else {
                            LaunchedEffect(match.id) { model.matchDetail(match) }
                            DisposableEffect(match.id) {
                                model.pinMatchDetail(match.id)
                                onDispose { model.unpinMatchDetail(match.id) }
                            }
                            MatchDetail(match, zone, null, { model.toggle(match.id) }, if (year == state.footballSeason) state.footballUpdated else route?.fetchedUtc,
                                year != state.footballSeason || state.footballCached,
                                state.footballDetails[match.id], match.id in state.detailLoading, state.detailErrors[match.id], { model.matchDetail(match, true) },
                                onTeam = { openTeam(it, match.competitionId, year) })
                        }
                    }
                    composable("team/{year}/{league}/{id}", arguments = listOf(
                        navArgument("year") { type = NavType.IntType }, navArgument("league") { type = NavType.StringType }, navArgument("id") { type = NavType.IntType })) { back ->
                        val id = back.arguments!!.getInt("id")
                        val year = back.arguments!!.getInt("year")
                        val requestedLeague = back.arguments!!.getString("league")!!
                        LaunchedEffect(year) { model.loadFootballRoute(year) }
                        val route = state.footballSeasons[year]
                        val routeMatches = if (year == state.footballSeason) state.matches else route?.matches.orEmpty()
                        val routeState = state.copy(footballSeason = year, matches = routeMatches, remoteMatches = routeMatches,
                            footballUpdated = if (year == state.footballSeason) state.footballUpdated else route?.fetchedUtc,
                            footballLoading = if (year == state.footballSeason) state.footballLoading else route == null,
                            footballTables = if (year == state.footballSeason) state.footballTables else route?.tables.orEmpty(),
                            footballError = if (year == state.footballSeason) state.footballError else state.routeErrors["football:$year"]
                                ?: route?.errors?.joinToString("\n")?.ifBlank { null })
                        val teamLeagues = remember(routeMatches, id) { routeMatches.filter { it.homeTeamId == id || it.awayTeamId == id }.mapNotNull { it.competitionId }.toSet() }
                        val league = if (requestedLeague != "auto") requestedLeague else
                            com.example.pitpitch.data.footballCompetitions.firstOrNull { !it.european && it.id in teamLeagues }?.id
                                ?: teamLeagues.firstOrNull() ?: "unknown"
                        val team = remember(routeMatches, id, state.homeTeam) { routeMatches.flatMap { match -> listOfNotNull(
                            match.homeTeamId?.let { com.example.pitpitch.data.FootballTeam(it, match.home, match.homeBadgeUrl) },
                            match.awayTeamId?.let { com.example.pitpitch.data.FootballTeam(it, match.away, match.awayBadgeUrl) }) }.find { it.id == id }
                            ?: state.homeTeam?.takeIf { it.id == id } }
                        if (team == null) {
                            val routeError = state.routeErrors["football:$year"]
                            when {
                                routeError != null -> StatePanel("球队详情不可用", routeError)
                                route == null -> Box(Modifier.fillMaxSize())
                                else -> StatePanel("球队详情不可用", "当前没有该球队记录。")
                            }
                        } else {
                            LaunchedEffect(team.id, year, league) { model.squadFor(year, league, team.id) }
                            TeamProfileScreen(routeState, team, year, league, zone, { openMatch(it, year) }) { model.squadFor(year, league, team.id, true) }
                        }
                    }
                    composable("race/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { back ->
                        val id = back.arguments!!.getString("id")!!
                        LaunchedEffect(id) { model.loadRaceRoute(id) }
                        val race = state.allRaces.find { it.id == id }
                        if (race == null) {
                            val year = id.removePrefix("f1-").substringBefore('-').toIntOrNull()
                            val routeError = year?.let { state.routeErrors["f1:$it"] }
                            when {
                                routeError != null -> StatePanel("分站详情不可用", routeError)
                                year == null || state.calendars[year].orEmpty().isEmpty() -> Box(Modifier.fillMaxSize())
                                else -> StatePanel("分站详情不可用", "请返回赛程加载对应赛季。")
                            }
                        } else {
                            LaunchedEffect(race.id) { model.results(race) }
                            DisposableEffect(race.id) {
                                model.startRacePolling(race)
                                onDispose { model.stopRacePolling(race.id) }
                            }
                            RaceDetail(race, zone, null, { model.toggle(race.id) }, model.updated(race.season), state.cached || race.season != state.season,
                                state.results[race.id], race.id in state.resultLoading, state.resultErrors[race.id], { model.results(race, true) }, state.qualifying[race.id], race.id in state.qualifyingLoading, state.qualifyingErrors[race.id])
                        }
                    }
                    composable("settings") { SettingsScreen(state, model::homeCompetition, { nav.navigate("teams") { launchSingleTop = true } }) { nav.navigate("about") { launchSingleTop = true } } }
                    composable("about") { AboutScreen() }
                    composable("teams") {
                        LaunchedEffect(Unit) { if (state.remoteMatches.isEmpty() && !state.footballLoading) model.refreshFootball() }
                        TeamSelectionScreen(state, model::refreshFootball) { model.homeTeam(it); nav.popBackStack() }
                    }
                    composable("standings") {
                        LaunchedEffect(state.season) { model.rankings() }
                        StandingsScreen(state, zone, model::rankings)
                    }
                }
            }
        }
    }
    }
}
