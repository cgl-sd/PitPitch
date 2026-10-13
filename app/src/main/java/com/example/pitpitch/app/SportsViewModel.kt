package com.example.pitpitch.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.DatabaseSeeder
import com.example.pitpitch.data.db.FootballStore
import com.example.pitpitch.data.db.PitPitchDatabase
import com.example.pitpitch.data.local.UserStore
import com.example.pitpitch.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset

// The ViewModel owns business state; screens only render and dispatch user actions.
// All football/F1 facts live in the local database ("backend"); the network only fills it.
class SportsViewModel @JvmOverloads constructor(application: Application,
    private val client: JsonClient = JsonClient(archive = SportsArchive(application, PitPitchDatabase.build(application)), database = PitPitchDatabase.build(application)),
    private val raceRepo: RaceRepository = JolpicaRaceRepository(),
    private val cache: RaceCache = RaceCache(application.getSharedPreferences("pit_pitch_user", 0)),
    db: PitPitchDatabase = PitPitchDatabase.build(application),
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val backend: LocalBackendRepository = localBackend(application),
    private val pollingDelay: suspend (Long) -> Unit = { delay(it) },
    private val store: UserStore = UserStore(application),
) : AndroidViewModel(application) {
    private companion object { const val INACTIVE_DETAIL_LIMIT = 128 }
    constructor(application: Application, savedStateHandle: SavedStateHandle) :
        this(application, db = PitPitchDatabase.build(application), savedStateHandle = savedStateHandle)
    data class State(
        val homeTeam: FootballTeam? = null,
        val hiddenHomeCompetitions: Set<String> = emptySet(),
        val followedCompetitions: Set<String> = emptySet(),
        val ready: Boolean = false, val demo: Boolean = false, val utc: Boolean = false,
        val favorites: Set<String> = emptySet(), val matches: List<FootballMatch> = emptyList(),
        val season: Int = Year.now().value, val calendars: Map<Int, List<F1Race>> = emptyMap(),
        val loading: Boolean = false, val error: String? = null, val cached: Boolean = false,
        val updated: String? = null, val storageError: String? = null,
        val footballSeason: Int = Year.now().value.let { if (java.time.LocalDate.now().monthValue < 7) it-1 else it },
        val footballLoading: Boolean = false, val footballError: String? = null, val footballCached: Boolean = false,
        val footballTableError: String? = null,
        val footballUpdated: String? = null, val remoteMatches: List<FootballMatch> = emptyList(),
        val footballCompetition: String = "eng.1",
        val footballTables: Map<String, RemoteSnapshot<List<FootballTable>>> = emptyMap(),
        val footballDetails: Map<String, RemoteSnapshot<FootballDetail>> = emptyMap(),
        val detailLoading: Set<String> = emptySet(), val detailErrors: Map<String, String> = emptyMap(),
        val squads: Map<String, RemoteSnapshot<FootballSquad>> = emptyMap(),
        val squadLoading: Set<String> = emptySet(), val squadErrors: Map<String, String> = emptyMap(),
        val leaderboards: Map<String, RemoteSnapshot<List<LeaderEntry>>> = emptyMap(),
        val leaderboardLoading: Set<String> = emptySet(), val leaderboardErrors: Map<String, String> = emptyMap(),
        val drivers: RemoteSnapshot<F1Rankings>? = null, val constructors: RemoteSnapshot<F1Rankings>? = null,
        val rankingRound: Int? = null, val rankingLoading: Boolean = false, val rankingError: String? = null,
        val results: Map<String, RemoteSnapshot<List<RankingEntry>>> = emptyMap(),
        val qualifying: Map<String, RemoteSnapshot<List<com.example.pitpitch.data.repository.QualifyingEntry>>> = emptyMap(),
        val qualifyingLoading: Set<String> = emptySet(), val qualifyingErrors: Map<String, String> = emptyMap(),
        val resultLoading: Set<String> = emptySet(), val resultErrors: Map<String, String> = emptyMap(),
        val footballSeasons: Map<Int, FootballArchiveSeason> = emptyMap(),
        val routeErrors: Map<String, String> = emptyMap(),
    ) { val races get() = calendars[season].orEmpty(); val allRaces get() = calendars.values.flatten() }
    private val archive = SportsArchive(application, db)
    private val dbStore = FootballStore(db)
    private val remoteRepo = EuropeanFootballRepository(client, dbStore, archive)
    private val seeder = DatabaseSeeder(db, dbStore, archive, client, cache)
    private val detailJobs = mutableMapOf<String, Job>()
    private val detailAccess = LinkedHashMap<String, Unit>(INACTIVE_DETAIL_LIMIT, .75f, true)
    private val detailPins = mutableMapOf<String, Int>()
    private var homeDetailIds: Set<String> = emptySet()
    private val squadJobs = mutableMapOf<String, Job>()
    private val mutable = MutableStateFlow(State(footballLoading = true, loading = true,
        season = savedStateHandle.get<Int>("f1Year")?.takeIf { it in f1ArchiveYears() } ?: Year.now().value,
        footballSeason = savedStateHandle.get<Int>("footballYear")?.takeIf { it in footballArchiveYears() } ?: currentFootballSeason(),
        footballCompetition = savedStateHandle.get<String>("competition")?.takeIf { id -> footballCompetitions.any { it.id == id } } ?: "eng.1"))
    val state = mutable.asStateFlow()
    private val dbReady = CompletableDeferred<Unit>()
    private var fetch: Job? = null
    private var footballFetch: Job? = null
    private var standingsFetch: Job? = null
    private var rankingFetch: Job? = null
    private var dayOverlayFetch: Job? = null
    private var dayPoll: Job? = null
    private var racePoll: Job? = null
    private var racePollRace: F1Race? = null
    private val leaderboardJobs = mutableMapOf<String, Job>()
    private val routeJobs = mutableMapOf<String, Job>()
    private val footballPublication = Mutex()
    private var foreground = true
    private var detailWarmup: Job? = null
    private val dayFetchAt = mutableMapOf<String, Instant>()
    private val resultJobs = mutableMapOf<String, Job>()
    private val qualifyingJobs = mutableMapOf<String, Job>()
    private val qualifyingRepository = com.example.pitpitch.data.repository.F1QualifyingRepository(client)
    private val f1Updated = mutableMapOf<Int, String>()
    init {
        savedStateHandle["f1Year"] = mutable.value.season
        savedStateHandle["footballYear"] = mutable.value.footballSeason
        savedStateHandle["competition"] = mutable.value.footballCompetition
        viewModelScope.launch {
            try {
                val started = System.currentTimeMillis()
                backend.prepare()
                seeder.seedIfNeeded()
                android.util.Log.i("PitPitchSeed", "seed took ${System.currentTimeMillis() - started}ms")
                dbReady.complete(Unit)
                f1Updated.putAll(dbStore.calendarTimes())
                val year = mutable.value.season
                val calendars = mapOf(year to dbStore.f1Races(year).first)
                mutable.update { it.copy(calendars = it.calendars + calendars) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(storageError = "本地数据库读取失败：${readableError(e)}") }
            } finally { dbReady.complete(Unit) }
            refreshRace(false)
        }
        viewModelScope.launch {
            try {
                store.migrate()
                store.settings.collect { settings ->
                    mutable.update { it.copy(ready = true, demo = false, utc = settings.utc,
                        homeTeam = settings.homeTeam, hiddenHomeCompetitions = settings.hiddenHomeCompetitions, followedCompetitions = settings.followedCompetitions, favorites = settings.favorites, matches = it.remoteMatches) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(ready = true, storageError = "本地偏好读取失败，请重新打开应用。") }
            }
        }
        // Facts remain readable even when preference migration or collection fails.
        loadFootball(false)
    }
    fun season(year: Int) { if (year in f1ArchiveYears() && year != mutable.value.season) {
        rankingFetch?.cancel()
        savedStateHandle["f1Year"] = year
        mutable.update { it.copy(season = year, drivers = null, constructors = null, rankingRound = null, rankingError = null, rankingLoading = false) }
        refreshRace(false)
    } }
    fun footballSeason(year: Int) { if (year in footballArchiveYears() && year != mutable.value.footballSeason) {
        dayOverlayFetch?.cancel()
        standingsFetch?.cancel()
        stopLivePolling()
        detailWarmup?.cancel()
        homeDetailIds = emptySet()
        trimDetails()
        savedStateHandle["footballYear"] = year
        mutable.update { it.copy(footballSeason = year, remoteMatches = emptyList(), matches = emptyList(), footballUpdated = null, footballCached = false, footballTables = emptyMap(), footballTableError = null) }
        loadFootball(false)
    } }
    fun footballCompetition(id: String) {
        if (footballCompetitions.any { it.id == id } && id != mutable.value.footballCompetition) {
            standingsFetch?.cancel()
            savedStateHandle["competition"] = id
            mutable.update { it.copy(footballCompetition = id, footballTableError = null) }
        }
    }
    fun refreshFootball() { loadFootball(true); refreshDayOverlay(true) }
    /** Refresh provider standings independently; keep visible cached rows until the response arrives. */
    fun refreshFootballStandings() {
        if (mutable.value.demo || standingsFetch?.isActive == true) return
        val year = mutable.value.footballSeason
        val league = mutable.value.footballCompetition
        mutable.update { it.copy(footballTableError = null) }
        standingsFetch = viewModelScope.launch {
            try {
                awaitDb()
                val response = remoteRepo.refreshStandings(league, year)
                mutable.update { state ->
                    if (state.footballSeason != year || state.footballCompetition != league) state else state.copy(
                        footballTables = state.footballTables + (league to (preferredLocalSnapshot(state.footballTables[league], response) ?: response)),
                        footballTableError = response.error)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { state -> if (state.footballSeason != year || state.footballCompetition != league) state else
                    state.copy(footballTableError = readableError(error)) }
            }
        }
    }
    /** Route reads never change the homepage season or competition selection. */
    fun loadFootballRoute(year: Int) {
        val key = "football:$year"
        if (year !in footballArchiveYears() || year in mutable.value.footballSeasons || routeJobs[key]?.isActive == true) return
        routeJobs[key] = viewModelScope.launch {
            try {
                awaitDb()
                footballPublication.withLock {
                    val response = remoteRepo.season(year)
                    mutable.update { it.copy(footballSeasons = it.footballSeasons + (year to response), routeErrors = it.routeErrors - key) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(routeErrors = it.routeErrors + (key to readableError(error))) }
            }
        }
    }
    fun loadRaceRoute(id: String) {
        val year = Regex("^f1-(\\d+)-\\d+$").matchEntire(id)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val key = "f1:$year"
        if (year !in f1ArchiveYears() || mutable.value.calendars[year].orEmpty().any { it.id == id } || routeJobs[key]?.isActive == true) return
        routeJobs[key] = viewModelScope.launch {
            try {
                awaitDb()
                val (rows, time) = dbStore.f1Races(year)
                val snapshot = preferredLocalSnapshot(localRaceSnapshot(year), time?.let { RemoteSnapshot(rows, it, cached = true) })
                val races = snapshot?.data ?: rows.ifEmpty { raceRepo.season(year) }
                snapshot?.fetchedUtc?.let { f1Updated[year] = it }
                mutable.update { it.copy(calendars = it.calendars + (year to races), routeErrors = it.routeErrors - key) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(routeErrors = it.routeErrors + (key to readableError(error))) }
            }
        }
    }
    private suspend fun awaitDb() = dbReady.await()
    private fun loadFootball(force: Boolean) {
        footballFetch?.cancel()
        val year = mutable.value.footballSeason
        mutable.update { it.copy(footballLoading = true, footballError = null, footballCached = it.remoteMatches.isNotEmpty()) }
        footballFetch = viewModelScope.launch {
            try {
                awaitDb()
                val response = footballPublication.withLock {
                    val response = remoteRepo.season(year, if (force) mutable.value.footballCompetition else null)
                    mutable.update { if (it.footballSeason != year) it else it.copy(remoteMatches = response.matches, matches = response.matches,
                        footballLoading = false, footballCached = !force || response.errors.isNotEmpty(), footballUpdated = response.fetchedUtc, footballError = response.errors.joinToString("\n").ifBlank { null },
                        footballTables = it.footballTables + response.tables.mapValues { (id, table) -> preferredLocalSnapshot(it.footballTables[id], table) ?: table }) }
                    response
                }
                detailWarmup?.cancel()
                detailWarmup = viewModelScope.launch warmup@ {
                    val visible = homeFootballRounds(response.matches, java.time.ZoneId.systemDefault(), Instant.now())
                        .filter { it.matches.firstOrNull()?.competitionId !in mutable.value.hiddenHomeCompetitions }
                        .flatMap { it.matches.take(3) }.take(9)
                    val recent = response.matches.filter { it.competitionId == mutable.value.footballCompetition && it.status.contains("完场") }
                        .sortedByDescending { it.kickoffUtc ?: it.date }.take(3)
                    val warmup = (visible + recent).distinctBy { it.id }.take(12)
                    if (mutable.value.footballSeason != year) return@warmup
                    homeDetailIds = warmup.mapTo(mutableSetOf()) { it.id }
                    trimDetails()
                    for (match in warmup) {
                        if (mutable.value.footballSeason != year) break
                        if (match.id in mutable.value.footballDetails || detailJobs[match.id]?.isActive == true) continue
                        val cached = try { remoteRepo.cachedMatchDetail(match) } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        } ?: continue
                        if (mutable.value.footballSeason == year && match.id !in mutable.value.detailLoading)
                            publishDetail(match.id, cached)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { if (it.footballSeason != year) it else it.copy(footballLoading = false, footballError = readableError(error)) }
            }
        }
    }
    private fun currentFootballSeason(): Int = Year.now().value.let { if (java.time.LocalDate.now().monthValue < 7) it - 1 else it }
    private fun displayZone(): ZoneId = ZoneId.systemDefault()
    /** Day-level refresh writes into the database, then the season list reloads from the same source. */
    fun refreshDayOverlay(force: Boolean = false) {
        if (mutable.value.demo) return
        val year = mutable.value.footballSeason
        if (year != currentFootballSeason()) return
        val league = footballCompetitions.firstOrNull { it.id == mutable.value.footballCompetition } ?: return
        val days = overlayUtcDays(Instant.now(), displayZone())
        val due = days.filter { day -> force || run {
            val last = dayFetchAt["${league.id}|$day"]
            last == null || java.time.Duration.between(last, Instant.now()).toMinutes() >= 1
        } }
        if (due.isEmpty()) return
        dayOverlayFetch?.cancel()
        dayOverlayFetch = viewModelScope.launch {
            try {
                awaitDb()
                remoteRepo.refreshDays(league.id, year, due)
                due.forEach { dayFetchAt["${league.id}|$it"] = Instant.now() }
                footballPublication.withLock {
                    val response = remoteRepo.season(year)
                    mutable.update { state -> if (state.footballSeason != year) state else state.copy(
                        remoteMatches = response.matches, matches = if (state.demo) state.matches else response.matches, footballUpdated = response.fetchedUtc,
                        footballTables = state.footballTables + response.tables.mapValues { (id, table) -> preferredLocalSnapshot(state.footballTables[id], table) ?: table }) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { if (it.footballSeason != year) it else it.copy(footballError = readableError(error)) }
            }
        }
    }
    /** Foreground-only ticker: refresh the day overlay every 90s while the selected league has a real match in progress or today. */
    fun startLivePolling() {
        if (!foreground || dayPoll?.isActive == true) return
        dayPoll = viewModelScope.launch {
            while (isActive) {
                pollingDelay(90_000)
                if (!hasTodayActivity()) break
                refreshDayOverlay()
            }
        }
    }
    private fun hasTodayActivity(): Boolean {
        val state = mutable.value
        if (state.demo) return false
        val zone = displayZone()
        val today = java.time.LocalDate.now(zone)
        return state.matches.any { !it.isDemo && (it.status.contains("进行") ||
            (it.competitionId == state.footballCompetition && matchDay(it, zone) == today)) }
    }
    fun stopLivePolling() { dayPoll?.cancel(); dayPoll = null }
    fun rankingRound(round: Int?) {
        mutable.update { it.copy(rankingRound = round, drivers = null, constructors = null) }
        loadRankings(false)
    }
    fun rankings() = loadRankings(false)
    fun refreshRankings() = loadRankings(true)
    private fun loadRankings(force: Boolean) {
        rankingFetch?.cancel()
        val year = mutable.value.season
        val round: Int? = null
        mutable.update { it.copy(rankingLoading = true, rankingError = null) }
        rankingFetch = viewModelScope.launch {
            val errors = mutableListOf<String>()
            try {
                awaitDb()
                if (!force) {
                    val drivers = dbStore.f1Standings(year, "driver", round?.toString())
                    val constructors = dbStore.f1Standings(year, "constructor", round?.toString())
                    if (drivers != null && constructors != null) {
                        mutable.update { it.copy(drivers = RemoteSnapshot(drivers.first, drivers.second ?: "", cached = true, archived = true),
                            constructors = RemoteSnapshot(constructors.first, constructors.second ?: "", cached = true, archived = true), rankingLoading = false) }
                        return@launch
                    }
                }
                for (teams in listOf(false, true)) {
                    try {
                        val kind = if (teams) "constructorstandings" else "driverstandings"
                        val url = "https://api.jolpi.ca/ergast/f1/$year${round?.let { "/$it" }.orEmpty()}/$kind.json?limit=100"
                        val response = client.load(url) { JolpicaAdapter.standings(it, teams, year, round) }
                        response.error?.let { errors += it }
                        dbStore.writeStandings(year, if (teams) "constructor" else "driver", response.data, response.fetchedUtc)
                        val stored = dbStore.f1Standings(year, if (teams) "constructor" else "driver", null)
                        val effective = stored?.let { response.copy(data = it.first, fetchedUtc = it.second ?: response.fetchedUtc) } ?: response
                        mutable.update { if (teams) it.copy(constructors = effective) else it.copy(drivers = effective) }
                    } catch (error: Exception) { if (error is CancellationException) throw error; errors += readableError(error) }
                }
                mutable.update { it.copy(rankingLoading = false, rankingError = errors.distinct().joinToString("\n").ifBlank { null }) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(rankingLoading = false, rankingError = readableError(error)) }
            }
        }
    }
    private fun loadQualifying(race: F1Race, force: Boolean) {
        if (!force && (race.id in mutable.value.qualifying || qualifyingJobs[race.id]?.isActive == true)) return
        qualifyingJobs[race.id]?.cancel()
        mutable.update { it.copy(qualifyingLoading = it.qualifyingLoading + race.id, qualifyingErrors = it.qualifyingErrors - race.id) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitDb()
                val response = qualifyingRepository.load(race, force)
                if (qualifyingJobs[race.id] === coroutineContext[Job])
                    mutable.update { it.copy(qualifying = it.qualifying + (race.id to response)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (qualifyingJobs[race.id] === coroutineContext[Job])
                    mutable.update { it.copy(qualifyingErrors = it.qualifyingErrors + (race.id to readableError(e))) }
            } finally {
                if (qualifyingJobs[race.id] === coroutineContext[Job]) {
                    qualifyingJobs.remove(race.id)
                    mutable.update { it.copy(qualifyingLoading = it.qualifyingLoading - race.id) }
                }
            }
        }
        qualifyingJobs[race.id] = job
        job.start()
    }
    fun results(race: F1Race, force: Boolean = false) {
        loadQualifying(race, force)
        if (!force && (race.id in mutable.value.results || resultJobs[race.id]?.isActive == true)) return
        resultJobs[race.id]?.cancel()
        mutable.update { it.copy(resultLoading = it.resultLoading + race.id, resultErrors = it.resultErrors - race.id) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitDb()
                if (!force) {
                    val (rows, time) = dbStore.f1Results(race.season, race.round)
                    if (rows.isNotEmpty()) {
                        if (resultJobs[race.id] === coroutineContext[Job])
                            mutable.update { it.copy(results = it.results + (race.id to RemoteSnapshot(rows, time ?: "", cached = true, archived = true))) }
                        return@launch
                    }
                }
                val url = "https://api.jolpi.ca/ergast/f1/${race.season}/${race.round}/results.json?limit=100"
                val response = client.load(url) { JolpicaAdapter.resultsForRound(it, race.season, race.round) }
                dbStore.writeResults(race.season, race.round, response.data, response.fetchedUtc)
                val stored = dbStore.f1Results(race.season, race.round)
                val effective = response.copy(data = stored.first, fetchedUtc = stored.second ?: response.fetchedUtc)
                f1Updated[race.season] = effective.fetchedUtc
                if (resultJobs[race.id] === coroutineContext[Job])
                    mutable.update { it.copy(results = it.results + (race.id to effective)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (resultJobs[race.id] === coroutineContext[Job])
                    mutable.update { it.copy(resultErrors = it.resultErrors + (race.id to readableError(error))) }
            } finally {
                if (resultJobs[race.id] === coroutineContext[Job]) {
                    resultJobs.remove(race.id)
                    mutable.update { it.copy(resultLoading = it.resultLoading - race.id) }
                }
            }
        }
        resultJobs[race.id] = job
        job.start()
    }
    private suspend fun localRaceSnapshot(year: Int) = withContext(Dispatchers.IO) {
        archive.read("https://api.jolpi.ca/ergast/f1/$year.json?limit=100") { F1Api.parseSeason(it, year) }
    }
    fun refresh() = refreshRace(true)
    /** Race calendars come from the database; force refresh goes to the network and writes back. */
    private fun refreshRace(force: Boolean) {
        fetch?.cancel()
        val year = mutable.value.season
        mutable.update { it.copy(loading = true, error = null, cached = it.calendars[year].orEmpty().isNotEmpty(), updated = f1Updated[year]) }
        fetch = viewModelScope.launch {
            try {
                awaitDb()
                val (races, time) = dbStore.f1Races(year)
                if (!force && races.isNotEmpty()) {
                    f1Updated[year] = time ?: f1Updated[year].orEmpty()
                    mutable.update { it.copy(calendars = it.calendars + (year to races), loading = false, cached = true, updated = f1Updated[year]) }
                } else {
                    val fresh = raceRepo.season(year, force = true)
                    val fetchedUtc = Instant.now().toString()
                    dbStore.writeRaces(year, fresh, fetchedUtc)
                    val (rows, storedTime) = dbStore.f1Races(year)
                    storedTime?.let { f1Updated[year] = it }
                    mutable.update { it.copy(calendars = it.calendars + (year to rows), loading = false, cached = races.isNotEmpty(), updated = storedTime) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val memory = mutable.value.calendars[year]?.let { rows ->
                    f1Updated[year]?.let { RemoteSnapshot(rows, it, cached = true) }
                }
                val stored = try {
                    val (rows, time) = dbStore.f1Races(year)
                    time?.let { RemoteSnapshot(rows, it, cached = true) }
                } catch (fallbackError: Exception) {
                    if (fallbackError is CancellationException) throw fallbackError
                    mutable.update { it.copy(storageError = "本地数据库读取失败：${readableError(fallbackError)}") }
                    null
                }
                val bundled = try {
                    localRaceSnapshot(year)
                } catch (fallbackError: Exception) {
                    if (fallbackError is CancellationException) throw fallbackError
                    mutable.update { it.copy(storageError = "本地数据库读取失败：${readableError(fallbackError)}") }
                    null
                }
                val fallback = preferredLocalSnapshot(preferredLocalSnapshot(bundled, stored), memory)
                fallback?.fetchedUtc?.let { f1Updated[year] = it }
                mutable.update { if (it.season != year) it else it.copy(loading = false, error = readableError(error),
                    calendars = if (fallback != null) it.calendars + (year to fallback.data) else it.calendars,
                    cached = fallback != null || it.calendars[year].orEmpty().isNotEmpty(), updated = fallback?.fetchedUtc ?: it.updated) }
            }
        }
    }
    fun startRacePolling(race: F1Race) {
        pauseRaceTicker()
        racePollRace = null
        if (race.season != Year.now().value) return
        val raceDay = runCatching { java.time.LocalDate.parse(race.date) }.getOrNull()
        val today = java.time.LocalDate.now()
        if (!race.isUpcoming() && raceDay != today && raceDay != today.minusDays(1)) return
        racePollRace = race
        launchRaceTicker(race)
    }
    private fun launchRaceTicker(race: F1Race) {
        if (!foreground) return
        racePoll = viewModelScope.launch {
            while (isActive) {
                pollingDelay(120_000)
                results(race, true)
            }
        }
    }
    private fun pauseRaceTicker() { racePoll?.cancel(); racePoll = null }
    fun stopRacePolling(id: String) {
        if (racePollRace?.id != id) return
        pauseRaceTicker()
        racePollRace = null
    }
    fun resumeForegroundPolling() {
        foreground = true
        startLivePolling()
        if (racePoll?.isActive != true) racePollRace?.let { startRacePolling(it) }
    }
    fun pauseForegroundPolling() { foreground = false; stopLivePolling(); pauseRaceTicker() }
    /** Reference counted so both visible destinations keep their facts during navigation animations. */
    fun pinMatchDetail(id: String) {
        detailPins[id] = (detailPins[id] ?: 0) + 1
        if (id in mutable.value.footballDetails) detailAccess[id] = Unit
    }
    fun unpinMatchDetail(id: String) {
        val remaining = (detailPins[id] ?: return) - 1
        if (remaining == 0) detailPins.remove(id) else detailPins[id] = remaining
        trimDetails()
    }
    private fun publishDetail(id: String, snapshot: RemoteSnapshot<FootballDetail>) {
        detailAccess[id] = Unit
        val details = mutable.value.footballDetails.toMutableMap()
        details[id] = snapshot
        evictDetails(details)
        mutable.update { it.copy(footballDetails = details) }
    }
    private fun trimDetails() {
        val current = mutable.value.footballDetails
        if (current.keys.count { it !in detailPins && it !in homeDetailIds } <= INACTIVE_DETAIL_LIMIT) return
        val details = current.toMutableMap()
        evictDetails(details)
        mutable.update { it.copy(footballDetails = details) }
    }
    private fun evictDetails(details: MutableMap<String, RemoteSnapshot<FootballDetail>>) {
        var excess = details.keys.count { it !in detailPins && it !in homeDetailIds } - INACTIVE_DETAIL_LIMIT
        val access = detailAccess.entries.iterator()
        while (excess > 0 && access.hasNext()) {
            val id = access.next().key
            if (id in detailPins || id in homeDetailIds) continue
            details.remove(id)
            access.remove()
            excess--
        }
    }
    fun matchDetail(match: FootballMatch, force: Boolean = false) {
        if (match.isDemo || match.competitionId == null) return
        if (!force && match.id in mutable.value.footballDetails) {
            detailAccess[match.id] = Unit
            return
        }
        if (!force && detailJobs[match.id]?.isActive == true) return
        detailJobs[match.id]?.cancel()
        mutable.update { it.copy(detailLoading = it.detailLoading + match.id, detailErrors = it.detailErrors - match.id) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitDb()
                val response = remoteRepo.matchDetail(match, force)
                if (detailJobs[match.id] === coroutineContext[Job])
                    publishDetail(match.id, response)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (detailJobs[match.id] === coroutineContext[Job])
                    mutable.update { it.copy(detailErrors = it.detailErrors + (match.id to readableError(e))) }
            } finally {
                if (detailJobs[match.id] === coroutineContext[Job]) {
                    detailJobs.remove(match.id)
                    mutable.update { it.copy(detailLoading = it.detailLoading - match.id) }
                }
            }
        }
        detailJobs[match.id] = job
        job.start()
    }
    /** Season scoring/assists ranking from the archived rosters of every team in the selected competition. */
    fun loadLeaderboards(force: Boolean = false) {
        if (mutable.value.demo) return
        val year = mutable.value.footballSeason; val league = mutable.value.footballCompetition
        val key = "$year|$league"
        if (!force && (key in mutable.value.leaderboards || leaderboardJobs[key]?.isActive == true)) return
        leaderboardJobs[key]?.cancel()
        mutable.update { it.copy(leaderboardLoading = it.leaderboardLoading + key, leaderboardErrors = it.leaderboardErrors - key) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitDb()
                val response = remoteRepo.leaderboards(year, league)
                if (leaderboardJobs[key] === coroutineContext[Job])
                    mutable.update { it.copy(leaderboards = it.leaderboards + (key to response)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (leaderboardJobs[key] === coroutineContext[Job])
                    mutable.update { it.copy(leaderboardErrors = it.leaderboardErrors + (key to readableError(e))) }
            } finally {
                if (leaderboardJobs[key] === coroutineContext[Job]) {
                    leaderboardJobs.remove(key)
                    mutable.update { it.copy(leaderboardLoading = it.leaderboardLoading - key) }
                }
            }
        }
        leaderboardJobs[key] = job
        job.start()
    }
    fun squad(team: Int, force: Boolean = false) = squadFor(mutable.value.footballSeason, mutable.value.footballCompetition, team, force)
    fun squadFor(year: Int, league: String, team: Int, force: Boolean = false) {
        if (year !in footballArchiveYears() || footballCompetitions.none { it.id == league }) return
        val key = "$year|$league|$team"
        if (!force && (key in mutable.value.squads || squadJobs[key]?.isActive == true)) return
        squadJobs[key]?.cancel()
        mutable.update { it.copy(squadLoading = it.squadLoading + key, squadErrors = it.squadErrors - key) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitDb()
                val response = remoteRepo.squad(year, league, team, force)
                if (squadJobs[key] === coroutineContext[Job])
                    mutable.update { it.copy(squads = it.squads + (key to response)) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (squadJobs[key] === coroutineContext[Job])
                    mutable.update { it.copy(squadErrors = it.squadErrors + (key to readableError(e))) }
            } finally {
                if (squadJobs[key] === coroutineContext[Job]) {
                    squadJobs.remove(key)
                    mutable.update { it.copy(squadLoading = it.squadLoading - key) }
                }
            }
        }
        squadJobs[key] = job
        job.start()
    }
    private fun save(action: suspend () -> Unit) = viewModelScope.launch {
        try { action(); mutable.update { it.copy(storageError = null) } }
        catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(storageError = "保存失败，请重试；本次修改尚未保存。") } }
    }
    fun updated(year: Int): String? = f1Updated[year]
    fun homeTeam(team: FootballTeam?) { save { store.homeTeam(team) } }
    fun homeCompetition(id: String, visible: Boolean) { save { store.homeCompetition(id, visible) } }
    fun followCompetition(id: String) { save { store.followCompetition(id) } }
    fun toggle(id: String) { save { store.toggle(id) } }
    fun demo(value: Boolean) { save { store.demo(value) } }
    fun utc(value: Boolean) { save { store.utc(value) } }
}
