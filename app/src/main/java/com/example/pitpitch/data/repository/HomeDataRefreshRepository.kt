package com.example.pitpitch.data.repository

import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.FootballStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue

data class HomeDataRefresh(
    val football: FootballArchiveSeason,
    val refreshedFootballTables: Set<String>,
    val leaderboards: Map<String, RemoteSnapshot<List<LeaderEntry>>>,
    val raceYear: Int,
    val races: List<F1Race>?,
    val drivers: RemoteSnapshot<F1Rankings>?,
    val constructors: RemoteSnapshot<F1Rankings>?,
    val updatedUtc: String?,
    val errors: List<String> = emptyList()
)

/** A round refresh also fetches the provider's season totals; never derive rankings from scores. */
class HomeDataRefreshRepository(private val football: EuropeanFootballRepository,
    private val store: FootballStore, private val client: JsonClient) {
    suspend fun refresh(year: Int, hidden: Set<String>, now: Instant, zone: ZoneId): HomeDataRefresh = supervisorScope {
        val fetched = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<String>()
        val refreshedTables = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val slots = Semaphore(4)
        suspend fun <T> retainedOnFailure(label: String = "", block: suspend () -> T): T? = try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                errors.add(label + readableError(error))
                null
            }
        fun <T> record(snapshot: RemoteSnapshot<T>, label: String = "") {
            if (!snapshot.cached && !snapshot.archived && snapshot.error == null) fetched.add(snapshot.fetchedUtc)
            snapshot.error?.let { errors.add(label + it) }
        }
        val baseline = football.season(year)
        val targets = homeRefreshMatches(baseline.matches, hidden, now, zone)
        val days = targets.groupBy { requireNotNull(it.competitionId) }.map { (id, matches) -> async {
            retainedOnFailure {
                val dates = homeRefreshDays(matches)
                if (football.refreshDays(id, year, dates, matches.mapTo(mutableSetOf()) { it.id })) {
                    val competition = footballCompetitions.first { it.id == id }
                    dates.mapNotNull { store.sourceTime(EspnFootballAdapter.dayUrl(competition, it)) }.forEach(fetched::add)
                }
            }
        } }
        val tables = footballCompetitions.filter { it.id !in hidden }.map { league -> async {
            retainedOnFailure("${league.title}积分：") {
                val snapshot = football.refreshStandingsOnce(league.id, year)
                record(snapshot, "${league.title}积分：")
                if (!snapshot.cached && !snapshot.archived && snapshot.error == null) refreshedTables.add(league.id)
            }
            retainedOnFailure("${league.title}榜单：") {
                val leaderboard = football.refreshLeaderboards(year, league.id, slots) { fetched.add(it) }
                record(leaderboard, "${league.title}榜单：")
                "$year|${league.id}" to leaderboard
            }
        } }
        val raceYear = now.atZone(zone).year
        val calendar = async {
            if ("f1" in hidden) return@async null
            retainedOnFailure("F1 赛程：") {
                homeRace(store.f1Races(raceYear).first, zone, now)?.let { race ->
                    val url = "https://api.jolpi.ca/ergast/f1/$raceYear/${race.round}.json?limit=100"
                    val snapshot = client.load(url) { body -> F1Api.parseSeason(body, raceYear).single().also {
                        require(it.round == race.round && it.circuitId == race.circuitId)
                    } }
                    store.writeRaceRound(snapshot.data, snapshot.fetchedUtc)
                    snapshot.error?.let { errors.add("F1 赛程：$it") }
                    if (store.sourceTime("f1:$raceYear:calendar:${race.round}") == snapshot.fetchedUtc) record(snapshot)
                }
                store.f1Races(raceYear).first
            }
        }
        val standings = listOf(false, true).map { teams -> async {
            if ("f1" in hidden) return@async null
            retainedOnFailure("F1 积分：") {
                val kind = if (teams) "constructor" else "driver"
                val url = "https://api.jolpi.ca/ergast/f1/$raceYear/${kind}standings.json?limit=100"
                val response = client.load(url) { JolpicaAdapter.standings(it, teams, raceYear) }
                store.writeStandings(raceYear, kind, response.data, response.fetchedUtc)
                val stored = store.f1Standings(raceYear, kind, null) ?: return@retainedOnFailure null
                val effective = response.copy(data = stored.first, fetchedUtc = stored.second ?: response.fetchedUtc,
                    cached = response.cached || stored.second != response.fetchedUtc)
                record(effective, "F1 积分：")
                effective
            }
        } }
        days.awaitAll()
        val leaders = tables.awaitAll().filterNotNull().toMap()
        val races = calendar.await()
        val rankings = standings.awaitAll()
        fetched.filter { parseProviderInstant(it) != null }.maxByOrNull { Instant.parse(parseProviderInstant(it)) }
            ?.let { store.recordHomeUpdated(it) }
        HomeDataRefresh(football.season(year), refreshedTables.toSet(), leaders, raceYear, races, rankings[0], rankings[1],
            store.homeUpdated(), errors.toList().distinct())
    }
}
