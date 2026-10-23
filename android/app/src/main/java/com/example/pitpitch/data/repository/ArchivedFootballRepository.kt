package com.example.pitpitch.data.repository

import com.example.pitpitch.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

internal class ArchivedFootballRepository(private val client: JsonClient, private val archive: SportsArchive) {
    suspend fun season(year: Int, refresh: String? = null): FootballArchiveSeason = withContext(Dispatchers.IO) {
        require(year in footballArchiveYears()) { "Season is outside the supported archive" }
        val matches = mutableListOf<FootballMatch>()
        val tables = mutableMapOf<String, RemoteSnapshot<List<FootballTable>>>()
        val times = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (league in footballCompetitions) {
            for (calendarYear in listOf(year, year + 1)) {
                val url = "${EspnFootballAdapter.SITE}${league.id}/scoreboard?dates=$calendarYear&limit=1000"
                try {
                    val response = if (refresh == league.id) client.load(url) { EspnFootballAdapter.scoreboard(it, league, year) }
                        else client.archived(url) { EspnFootballAdapter.scoreboard(it, league, year) } ?: archive.read(url) { EspnFootballAdapter.scoreboard(it, league, year) }
                    if (response != null) { matches += response.data.matches; times += response.fetchedUtc; response.error?.let { errors += "${league.title}：$it" } }
                    else errors += "${league.title} $calendarYear 年归档未提供"
                } catch (e: Exception) { if (e is CancellationException) throw e; errors += "${league.title}：${readableError(e)}" }
            }
            val url = "${EspnFootballAdapter.WEB}${league.id}/standings?season=$year"
            try {
                val response = if (refresh == league.id) client.load(url) { EspnFootballAdapter.tables(it, year) }
                    else client.archived(url) { EspnFootballAdapter.tables(it, year) } ?: archive.read(url) { EspnFootballAdapter.tables(it, year) }
                if (response != null) tables[league.id] = response.copy(data = archive.tableNotes(url, year, response.data))
            } catch (e: Exception) { if (e is CancellationException) throw e; errors += "${league.title}积分：${readableError(e)}" }
        }
        val rounds = archive.footballRounds(year)
        FootballArchiveSeason(matches.distinctBy { it.id }.map { applyFootballRound(it, year, rounds[it.id]) }.sortedBy { it.kickoffUtc ?: it.date }, tables, times.minOrNull(), errors.distinct())
    }
    suspend fun squad(year: Int, competition: String, team: Int, force: Boolean = false): RemoteSnapshot<FootballSquad> {
        require(year in footballArchiveYears())
        require(team >= 1_000_000)
        val url = "${EspnFootballAdapter.SITE}$competition/teams/${team - 1_000_000}/roster?season=$year"
        return withContext(Dispatchers.IO) { if (!force) client.archived(url) { EspnFootballAdapter.squad(it, year) }
            ?: client.load(url) { EspnFootballAdapter.squad(it, year) }
        else client.load(url) { EspnFootballAdapter.squad(it, year) } }
    }
}
