package com.example.pitpitch.data.repository

import com.example.pitpitch.data.*
import com.example.pitpitch.data.EspnFootballAdapter
import com.example.pitpitch.data.EspnMatchAdapter
import com.example.pitpitch.data.FootballArchiveSeason
import com.example.pitpitch.data.FootballDetail
import com.example.pitpitch.data.FootballMatch
import com.example.pitpitch.data.FootballSquad
import com.example.pitpitch.data.JsonClient
import com.example.pitpitch.data.LeaderEntry
import com.example.pitpitch.data.RemoteSnapshot
import com.example.pitpitch.data.db.FootballStore
import com.example.pitpitch.data.footballCompetitions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Football reads come from the local database; the network only fills it in.
 * RemoteSnapshot fields (fetchedUtc/cached) keep the existing UI contract unchanged.
 */
class EuropeanFootballRepository(private val client: JsonClient, private val store: FootballStore?, private val archive: SportsArchive? = null) {
    constructor(client: JsonClient, archive: SportsArchive) : this(client, null, archive)
    private val local by lazy { ArchivedFootballRepository(client, requireNotNull(archive)) }
    suspend fun season(year: Int, refresh: String? = null): FootballArchiveSeason = withContext(Dispatchers.IO) {
        require(year in footballArchiveYears())
        if (store == null) {
            if (refresh == null) return@withContext local.season(year)
            val league = footballCompetitions.firstOrNull { it.id == refresh } ?: return@withContext local.season(year)
            val baseline = local.season(year)
            val errors = baseline.errors.toMutableList()
            val refreshedMatches = mutableListOf<FootballMatch>()
            for (calendarYear in listOf(year, year + 1)) {
                try {
                    val response = client.load(EspnFootballAdapter.scheduleUrl(league.id, calendarYear)) {
                        EspnFootballAdapter.scoreboard(it, league, year)
                    }
                    refreshedMatches += response.data.matches
                    response.error?.let { errors += "${league.title}：$it" }
                } catch (e: Exception) { if (e is CancellationException) throw e; errors += "${league.title}：${readableError(e)}" }
            }
            val tables = baseline.tables.toMutableMap()
            try {
                val response = refreshStandings(league.id, year)
                tables[league.id] = response
                response.error?.let { errors += "${league.title}积分：$it" }
            } catch (e: Exception) { if (e is CancellationException) throw e; errors += "${league.title}积分：${readableError(e)}" }
            val rounds = archive?.footballRounds(year).orEmpty()
            val matches = (baseline.matches + refreshedMatches).associateBy { it.id }.values
                .map { applyFootballRound(it, year, rounds[it.id]) }.sortedBy { it.kickoffUtc ?: it.date }
            return@withContext baseline.copy(matches = matches, tables = tables, errors = errors.distinct())
        }
        val errors = mutableListOf<String>()
        val refreshedTables = mutableMapOf<String, RemoteSnapshot<List<FootballTable>>>()
        if (refresh != null) {
            val league = footballCompetitions.firstOrNull { it.id == refresh }
            if (league != null) {
                for (calendarYear in listOf(year, year + 1)) {
                    val url = EspnFootballAdapter.scheduleUrl(league.id, calendarYear)
                    try {
                        val raw = client.load(url) { EspnFootballAdapter.scoreboard(it, league, year) }
                        store.writeMatches(raw.data.matches, league.id, league.title, year, raw.fetchedUtc, url, "schedule")
                        raw.data.timeline.forEach { (matchId, events) ->
                            store.writeTimeline(matchId, events, raw.fetchedUtc, url)
                        }
                        raw.error?.let { errors += "${league.title}：$it" }
                    } catch (e: Exception) { if (e is CancellationException) throw e; errors += "${league.title}：${readableError(e)}" }
                }
                try {
                    val response = refreshStandings(league.id, year)
                    refreshedTables[league.id] = response
                    response.error?.let { errors += "${league.title}积分：$it" }
                } catch (e: Exception) { if (e is CancellationException) throw e; errors += "${league.title}积分：${readableError(e)}" }
            }
        }
        val urls = footballCompetitions.flatMap { listOf(
            EspnFootballAdapter.scheduleUrl(it.id, year), EspnFootballAdapter.scheduleUrl(it.id, year + 1), EspnFootballAdapter.standingsUrl(it.id, year)) }
        val rounds = archive?.footballRounds(year).orEmpty()
        val matches = store.seasonMatches(year).map { applyFootballRound(it, year, rounds[it.id]) }
        val tables = footballCompetitions.mapNotNull { competition ->
            store.competitionTables(competition.id, year)?.let { (tables, time) ->
                val url = EspnFootballAdapter.standingsUrl(competition.id, year)
                competition.id to (refreshedTables[competition.id]
                    ?: RemoteSnapshot(archive?.tableNotes(url, year, tables) ?: tables, time ?: "", cached = true, archived = true))
            }
        }.toMap()
        FootballArchiveSeason(matches, tables, store.seasonTime(year, urls), errors.distinct())
    }
    /** Validate the requested season and usable tables before JsonClient updates its response cache. */
    suspend fun refreshStandings(leagueId: String, year: Int): RemoteSnapshot<List<FootballTable>> = withContext(Dispatchers.IO) {
        require(year in footballArchiveYears())
        require(footballCompetitions.any { it.id == leagueId })
        val url = EspnFootballAdapter.standingsUrl(leagueId, year)
        try {
            val response = client.load(url) { body ->
                EspnFootballAdapter.tables(body, year).also { tables ->
                    require(tables.isNotEmpty() && tables.all { it.entries.isNotEmpty() }) { "Provider returned empty standings" }
                }
            }
            store?.writeStandings(leagueId, year, response.data, response.fetchedUtc)
            val effective = store?.competitionTables(leagueId, year)
            val tables = effective?.first ?: response.data
            val time = effective?.second ?: response.fetchedUtc
            response.copy(
                data = archive?.tableNotes(url, year, tables) ?: tables,
                fetchedUtc = time,
                cached = response.cached || time != response.fetchedUtc,
                archived = response.archived || time != response.fetchedUtc
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val (tables, time) = store?.competitionTables(leagueId, year) ?: throw e
            RemoteSnapshot(archive?.tableNotes(url, year, tables) ?: tables, time ?: "",
                cached = true, error = readableError(e), archived = true)
        }
    }
    suspend fun squad(year: Int, competition: String, team: Int, force: Boolean = false): RemoteSnapshot<FootballSquad> {
        require(team >= 1_000_000)
        if (store == null) return local.squad(year, competition, team, force)
        if (!force) store.squad(competition, year, team)?.let { (squad, time) ->
            return RemoteSnapshot(squad, time, cached = true, archived = true)
        }
        val url = "${EspnFootballAdapter.SITE}$competition/teams/${team - 1_000_000}/roster?season=$year"
        val snapshot = withContext(Dispatchers.IO) { client.load(url) { EspnFootballAdapter.squad(it, year) } }
        store.writeSquad(competition, year, team, store.teamName(team) ?: "球队 ${team - 1_000_000}", snapshot.data, snapshot.fetchedUtc)
        val (effective, time) = requireNotNull(store.squad(competition, year, team))
        return snapshot.copy(data = effective, fetchedUtc = time,
            cached = snapshot.cached || time != snapshot.fetchedUtc,
            archived = snapshot.archived || time != snapshot.fetchedUtc)
    }
    suspend fun matchDetail(match: FootballMatch, force: Boolean = false): RemoteSnapshot<FootballDetail> {
        if (!force) cachedMatchDetail(match)?.let { return it }
        val url = "${EspnFootballAdapter.SITE}${match.competitionId}/summary?event=${match.id.substringAfterLast('-')}"
        val snapshot = withContext(Dispatchers.IO) { client.load(url) { EspnMatchAdapter.detail(it, match) } }
        store?.writeDetail(match.id, snapshot.data, snapshot.fetchedUtc, url)
        val effective = store?.detail(match) ?: return snapshot
        val time = detailTime(match) ?: snapshot.fetchedUtc
        return snapshot.copy(data = effective, fetchedUtc = time,
            cached = snapshot.cached || time != snapshot.fetchedUtc,
            archived = snapshot.archived || time != snapshot.fetchedUtc)
    }
    /** Read and warm local details without making any network request. */
    suspend fun cachedMatchDetail(match: FootballMatch): RemoteSnapshot<FootballDetail>? {
        store?.detail(match)?.let { detail ->
            val time = detailTime(match) ?: ""
            if (detail.events.any { it.type.startsWith("goal") && footballEventScore(it, match) == null }) {
                val raw = withContext(Dispatchers.IO) {
                    client.archived("${EspnFootballAdapter.SITE}${match.competitionId}/summary?event=${match.id.substringAfterLast('-')}") { EspnMatchAdapter.detail(it, match) }
                }
                // Recover full source text from the matching cached response without replacing newer facts.
                if (raw != null && raw.fetchedUtc == time && raw.data.lineups.toSet() == detail.lineups.toSet() && raw.data.statistics.toSet() == detail.statistics.toSet() &&
                    raw.data.events.map { it.copy(text = "") } == detail.events.map { it.copy(text = "") } && raw.data.events != detail.events) {
                    store.writeDetail(match.id, raw.data, raw.fetchedUtc)
                    return raw
                }
            }
            return RemoteSnapshot(detail, time, cached = true, archived = true)
        }
        val url = "${EspnFootballAdapter.SITE}${match.competitionId}/summary?event=${match.id.substringAfterLast('-')}"
        val snapshot = withContext(Dispatchers.IO) { client.archived(url) { EspnMatchAdapter.detail(it, match) } } ?: return null
        store?.writeDetail(match.id, snapshot.data, snapshot.fetchedUtc, url)
        val effective = store?.detail(match) ?: return snapshot
        val time = detailTime(match) ?: snapshot.fetchedUtc
        return snapshot.copy(data = effective, fetchedUtc = time,
            cached = true, archived = true)
    }
    /** Day-level refresh: writes into the database (freshness-checked) instead of keeping overlay snapshots. */
    suspend fun refreshDays(leagueId: String, year: Int, utcDays: List<String>): Boolean {
        require(year in footballArchiveYears())
        val store = store ?: return false
        val league = footballCompetitions.first { it.id == leagueId }
        var any = false
        for (day in utcDays) {
            val url = EspnFootballAdapter.dayUrl(league, day)
            try {
                val raw = withContext(Dispatchers.IO) { client.load(url) { EspnFootballAdapter.scoreboard(it, league, year) } }
                store.writeMatches(raw.data.matches, league.id, league.title, year, raw.fetchedUtc, url, "day")
                raw.data.timeline.forEach { (matchId, events) ->
                    store.writeTimeline(matchId, events, raw.fetchedUtc, url, "day")
                }
                if (!raw.cached && !raw.archived && raw.error == null && store.sourceTime(url) == raw.fetchedUtc) any = true
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        if (any) {
            try {
                refreshStandings(leagueId, year)
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        return any
    }
    suspend fun leaderboards(year: Int, league: String): RemoteSnapshot<List<LeaderEntry>> {
        val (entries, time) = requireNotNull(store).leaderboards(league, year)
        return RemoteSnapshot(entries, time, cached = true, archived = true)
    }
    private suspend fun detailTime(match: FootballMatch): String? =
        store?.sourceTime("${EspnFootballAdapter.SITE}${match.competitionId}/summary?event=${match.id.substringAfterLast('-')}")
}
