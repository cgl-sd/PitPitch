package com.example.pitpitch.data.db

import com.example.pitpitch.data.FootballMatch
import com.example.pitpitch.data.EspnFootballAdapter
import com.example.pitpitch.data.FootballPlayer
import com.example.pitpitch.data.FootballSquad
import com.example.pitpitch.data.FootballTable
import com.example.pitpitch.data.F1Race
import com.example.pitpitch.data.F1Api
import com.example.pitpitch.data.F1Session
import com.example.pitpitch.data.FootballDetail
import com.example.pitpitch.data.LeaderEntry
import com.example.pitpitch.data.MatchEvent
import com.example.pitpitch.data.MatchStatistic
import com.example.pitpitch.data.RankingEntry
import com.example.pitpitch.data.F1Rankings
import com.example.pitpitch.data.parseProviderInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import com.example.pitpitch.data.FootballDetailEvent
import org.json.JSONObject
import org.json.JSONArray

private val sessionOrder = compareBy<F1Session> { F1Api.sessionOrder(it.title) }

/**
 * The device-side "backend": every read and write for football and F1 facts goes through here.
 * Row timestamps retain resource acquisition times. Newer cache wins; equal-time imports may fill bundled metadata.
 */
class FootballStore(private val db: PitPitchDatabase) {
    private fun now(): String = Instant.now().toString()
    private fun incomingWins(incoming: String, existing: String?): Boolean {
        val time = runCatching { Instant.parse(incoming) }.getOrNull() ?: return false
        val old = existing?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return old == null || !time.isBefore(old)
    }

    // ---- football writes ----
    suspend fun writeMatches(matches: List<FootballMatch>, competitionId: String, competitionTitle: String, season: Int, fetchedUtc: String = now(), sourceUrl: String? = null, sourceKind: String = "schedule"): Unit =
        withContext(Dispatchers.IO) {
            db.transaction {
                val existing = db.matches().freshness(matches.map { it.id }).associate { it.matchId to it.updatedAt }
                val eligible = matches.filter { incomingWins(fetchedUtc, existing[it.id]) }
                db.matches().upsert(eligible.map { m ->
                    MatchRow(m.id, competitionId, competitionTitle, season, m.stage, m.date, m.kickoffUtc, m.status, m.score,
                        m.liveDetail, m.homeTeamId, m.home, m.homeShort, m.homeBadgeUrl, m.awayTeamId, m.away, m.awayShort,
                        m.awayBadgeUrl, m.stadium, m.leg, fetchedUtc)
                })
                sourceUrl?.let { writeSource(it, fetchedUtc, sourceKind) }
            }
        }
    suspend fun writeTimeline(matchId: String, events: List<MatchEvent>, fetchedUtc: String = now(), sourceUrl: String? = null, sourceKind: String = "schedule") = db.transaction {
        if (events.isEmpty()) return@transaction
        val existing = db.matchDetails().events(matchId)
        // A validated detail timeline, including an authoritative empty one, wins over scoreboard summaries.
        if (db.sourceLog().time("detail:$matchId:events") != null || existing.any { it.richEvent != null } ||
            !incomingWins(fetchedUtc, db.sourceLog().time("timeline:$matchId") ?: existing.maxOfOrNull { it.updatedAt })) return@transaction
        db.matchDetails().clearEvents(matchId)
        db.matchDetails().insertEvents(events.mapIndexed { seq, e -> EventRow(matchId = matchId, minute = e.minute, added = e.added, seq = seq, label = e.label, updatedAt = fetchedUtc) })
        db.sourceLog().upsert(listOf(SourceRow("timeline:$matchId", fetchedUtc, "timeline")))
        sourceUrl?.let { writeSource(it, fetchedUtc, sourceKind) }
    }
    suspend fun writeStandings(competitionId: String, season: Int, tables: List<FootballTable>, originFetchedUtc: String) =
        withContext(Dispatchers.IO) {
            if (tables.isEmpty()) return@withContext
            db.transaction {
                val key = "standings:$competitionId:$season"
                if (!incomingWins(originFetchedUtc, db.sourceLog().time(key))) return@transaction
                db.standings().clear(competitionId, season)
                db.standings().upsert(tables.flatMap { table ->
                    table.entries.map { StandingRow(competitionId, season, table.name, it.position, it.team.id, it.team.name,
                        it.played, it.won, it.drawn, it.lost, it.goalsFor, it.goalsAgainst, it.difference, it.points, it.deductions,
                        originFetchedUtc, it.team.badgeUrl, it.providerNote) }
                })
                db.sourceLog().upsert(listOf(SourceRow(key, originFetchedUtc, "standings")))
            }
        }
    suspend fun writeSquad(competitionId: String, season: Int, teamId: Int, teamName: String, squad: FootballSquad, fetchedUtc: String = now()): Unit =
        withContext(Dispatchers.IO) {
            db.transaction {
                if (!incomingWins(fetchedUtc, db.players().squadTime(competitionId, season, teamId))) return@transaction
                db.players().clearTeam(competitionId, season, teamId)
                db.players().upsert(squad.players.map {
                    PlayerRow(it.id, season, competitionId, teamId, teamName, it.name, it.position, it.jersey,
                        it.appearances, it.goals, it.assists, it.photoUrl, fetchedUtc)
                })
                db.sourceLog().upsert(listOf(SourceRow("squad:$competitionId:$teamId:$season", fetchedUtc, "squad")))
                writeSource("${EspnFootballAdapter.SITE}$competitionId/teams/${teamId - 1_000_000}/roster?season=$season", fetchedUtc, "roster")
            }
        }
    suspend fun writeDetail(matchId: String, detail: FootballDetail, fetchedUtc: String = now(), sourceUrl: String? = null) = withContext(Dispatchers.IO) {
        db.transaction {
            val url = sourceUrl ?: matchId.takeIf { it.startsWith("fb-espn-") }?.let {
                "${EspnFootballAdapter.SITE}${it.removePrefix("fb-espn-").substringBeforeLast('-')}/summary?event=${it.substringAfterLast('-')}"
            }
            val oldTimes = listOfNotNull(db.sourceLog().time("detail:$matchId"), db.matchDetails().detailTime(matchId),
                url?.let { db.sourceLog().time(it) })
            if (oldTimes.any { !incomingWins(fetchedUtc, it) }) return@transaction
            if (!detail.lineupsProvided && !detail.statisticsProvided && !detail.eventsProvided) return@transaction
            if (detail.lineupsProvided) {
                db.matchDetails().clearLineups(matchId)
                db.matchDetails().clearTeams(matchId)
                db.matchDetails().upsertTeams(detail.lineups.map { MatchTeamRow(matchId, it.teamId, it.formation, fetchedUtc) })
                val rows = detail.lineups.flatMap { lineup ->
                    val batch = { role: String, players: List<FootballPlayer> ->
                        players.mapIndexed { i, p -> LineupRow(matchId, lineup.teamId, role, i, p.id, p.name, p.jersey, p.position, p.photoUrl, p.goals, p.appearances, p.assists, fetchedUtc) }
                    }
                    batch("start", lineup.starters) + batch("sub", lineup.substitutes) + batch("unknown", lineup.unknown)
                }
                db.matchDetails().upsertLineups(rows)
                db.sourceLog().upsert(listOf(SourceRow("detail:$matchId:lineups", fetchedUtc, "detailSection")))
            }
            if (detail.statisticsProvided) {
                db.matchDetails().clearStats(matchId)
                db.matchDetails().upsertStats(detail.statistics.filter { it.home != null || it.away != null }
                    .map { StatRow(matchId, it.label, it.home, it.away, fetchedUtc) })
                db.sourceLog().upsert(listOf(SourceRow("detail:$matchId:statistics", fetchedUtc, "detailSection")))
            }
            if (detail.eventsProvided) {
                db.matchDetails().clearEvents(matchId)
                db.matchDetails().insertEvents(detail.events.mapIndexed { seq, e ->
                    val body = JSONObject().put("id", e.id).put("type", e.type).put("clock", e.clock).put("seconds", e.seconds)
                        .put("period", e.period).put("teamId", e.teamId).put("players", JSONArray(e.players)).put("text", e.text)
                    EventRow(matchId = matchId, minute = e.seconds?.div(60)?.toInt() ?: 0, added = 0, seq = seq,
                        label = e.text, updatedAt = fetchedUtc, richEvent = body.toString())
                })
                db.sourceLog().upsert(listOf(SourceRow("detail:$matchId:events", fetchedUtc, "detailSection")))
            }
            db.sourceLog().upsert(listOf(SourceRow("detail:$matchId", fetchedUtc, "detail")))
            url?.let { writeSource(it, fetchedUtc, "detail") }
        }
    }
    suspend fun writeSource(url: String, fetchedUtc: String, kind: String) = db.transaction {
        if (incomingWins(fetchedUtc, db.sourceLog().time(url))) db.sourceLog().upsert(listOf(SourceRow(url, fetchedUtc, kind)))
    }

    // ---- football reads ----
    suspend fun seasonMatches(season: Int): List<FootballMatch> = withContext(Dispatchers.IO) {
        db.matches().bySeason(season).map { it.model() }
    }
    suspend fun competitionTables(competitionId: String, season: Int): Pair<List<FootballTable>, String?>? = withContext(Dispatchers.IO) {
        val rows = db.standings().byCompetition(competitionId, season)
        if (rows.isEmpty()) return@withContext null
        val time = db.sourceLog().time("standings:$competitionId:$season") ?: rows.minOf { it.updatedAt }
        rows.groupBy { it.groupName }.map { (group, entries) ->
            FootballTable(group, entries.map { row ->
                com.example.pitpitch.data.FootballStanding(
                    com.example.pitpitch.data.FootballTeam(row.teamId, row.teamName, row.badgeUrl), row.position, row.played,
                    row.won, row.drawn, row.lost, row.goalsFor, row.goalsAgainst, row.difference, row.points, row.deductions, row.providerNote)
            })
        } to time
    }
    suspend fun squad(competitionId: String, season: Int, teamId: Int): Pair<FootballSquad, String>? = withContext(Dispatchers.IO) {
        db.transaction {
            val rows = db.players().byTeam(competitionId, season, teamId)
            val time = db.players().squadTime(competitionId, season, teamId) ?: return@transaction null
            FootballSquad(season, rows.map { FootballPlayer(it.playerId, it.name, it.jersey, it.position, it.goals, it.appearances, it.assists, it.photoUrl) },
                coach = null) to time
        }
    }
    suspend fun detail(match: FootballMatch): FootballDetail? = withContext(Dispatchers.IO) {
        db.transaction {
        val teamRows = db.matchDetails().teams(match.id)
        val lineupRows = db.matchDetails().lineups(match.id)
        val lineups = teamRows.map { team ->
            val rows = lineupRows.filter { it.teamId == team.teamId }
            val player = { row: LineupRow -> FootballPlayer(row.playerId, row.playerName, row.jersey, row.position, row.goals, row.appearances, row.assists, row.photoUrl) }
            com.example.pitpitch.data.MatchLineup(team.teamId, team.formation,
                rows.filter { it.role == "start" }.map(player), rows.filter { it.role == "sub" }.map(player),
                rows.filter { it.role == "unknown" }.map(player))
        }
        val statistics = db.matchDetails().stats(match.id).map { MatchStatistic(it.label, it.home, it.away) }
        val events = db.matchDetails().events(match.id).mapNotNull { row ->
            row.richEvent?.let { raw -> runCatching {
                val e = JSONObject(raw)
                FootballDetailEvent(e.getString("id"), e.getString("type"), e.getString("clock"),
                    if (e.isNull("seconds")) null else e.getDouble("seconds"), if (e.isNull("period")) null else e.getInt("period"),
                    if (e.isNull("teamId")) null else e.getInt("teamId"), e.getJSONArray("players").let { a -> (0 until a.length()).map { a.getString(it) } }, e.getString("text"))
            }.getOrNull() }
        }
        val recorded = db.sourceLog().time("detail:${match.id}") != null
        if (!recorded && lineups.isEmpty() && statistics.isEmpty() && events.isEmpty()) return@transaction null
        FootballDetail(lineups, statistics, events,
            lineups.isNotEmpty() || db.sourceLog().time("detail:${match.id}:lineups") != null,
            statistics.isNotEmpty() || db.sourceLog().time("detail:${match.id}:statistics") != null,
            events.isNotEmpty() || db.sourceLog().time("detail:${match.id}:events") != null)
        }
    }
    suspend fun leaderboards(competitionId: String, season: Int): Pair<List<LeaderEntry>, String> = withContext(Dispatchers.IO) {
        val rows = db.players().bySeason(competitionId, season)
        val entries = rows.map { LeaderEntry(it.name, it.teamName, it.goals?.toIntOrNull(), it.assists?.toIntOrNull(), it.appearances?.toIntOrNull(), it.teamId, it.playerId) }
            .sortedWith(compareByDescending<LeaderEntry> { it.goals }.thenByDescending { it.assists }.thenByDescending { it.appearances }.thenBy { it.name })
        entries to (rows.minOfOrNull { it.updatedAt } ?: "")
    }
    suspend fun seasonTime(season: Int, urls: List<String>): String? = withContext(Dispatchers.IO) {
        db.sourceLog().times(urls).minOrNull() ?: db.matches().seasonTime(season)
    }

    // ---- F1 writes ----
    suspend fun writeRaces(season: Int, races: List<F1Race>, originFetchedUtc: String) = db.transaction {
        require(races.all { it.season == season }) { "Unrelated calendar season" }
        require(races.map { it.round }.distinct().size == races.size) { "Duplicate calendar round" }
        val stamp = originFetchedUtc
        if (!incomingWins(stamp, db.sourceLog().time("f1:$season"))) return@transaction
        db.f1().clearRaces(season); db.f1().clearSessions(season)
        db.f1().upsertRaces(races.map { F1RaceRow(season, it.round, it.name, it.circuit, it.country, it.date, it.utcTime, it.circuitId, stamp) })
        val sessionRows = races.flatMap { race ->
            race.sessions.map { F1SessionRow(season, race.round, it.title, it.date, it.time, stamp) }
        }
        db.f1().upsertSessions(sessionRows)
        db.sourceLog().upsert(listOf(SourceRow("f1:$season", originFetchedUtc, "calendar")))
    }
    suspend fun writeResults(season: Int, round: Int, entries: List<RankingEntry>, originFetchedUtc: String) = db.transaction {
        val stamp = originFetchedUtc
        if (!incomingWins(stamp, db.sourceLog().time("f1:$season:$round"))) return@transaction
        require(entries.map { it.position }.distinct().size == entries.size)
        require(entries.map { it.driverId ?: it.name }.distinct().size == entries.size)
        db.f1().clearResults(season, round)
        db.f1().upsertResults(entries.map { F1ResultRow(season, round, it.position, it.name, it.team, it.points, it.detail, stamp, it.driverId, it.constructorId, it.grid, it.nationality) })
        db.sourceLog().upsert(listOf(SourceRow("f1:$season:$round", originFetchedUtc, "results")))
    }
    /** Repair R-key collisions only when every retained fact agrees; preserve individual clocks. */
    suspend fun repairLegacyResults(season: Int, round: Int, full: List<RankingEntry>, sourceTime: String) = db.transaction {
        val existing = db.f1().results(season, round)
        if (existing.none { it.position.toIntOrNull() == null } || full.isEmpty()) return@transaction
        val byName = full.groupBy { it.name }
        if (existing.any { row ->
            val entry = byName[row.name]?.singleOrNull()
            entry == null || row.team != entry.team || row.points != entry.points || row.detail != entry.detail ||
                (row.position.toIntOrNull() != null && row.position != entry.position) ||
                (row.driverId != null && row.driverId != entry.driverId) ||
                (row.constructorId != null && row.constructorId != entry.constructorId) ||
                (row.grid != null && row.grid != entry.grid) || (row.nationality != null && row.nationality != entry.nationality)
        }) return@transaction
        val retained = existing.associateBy { it.name }
        val repaired = full.map { entry ->
            retained[entry.name]?.copy(position = entry.position, driverId = entry.driverId, constructorId = entry.constructorId,
                grid = entry.grid, nationality = entry.nationality) ?: F1ResultRow(season, round, entry.position, entry.name,
                entry.team, entry.points, entry.detail, sourceTime, entry.driverId, entry.constructorId, entry.grid, entry.nationality)
        }
        db.f1().clearResults(season, round)
        db.f1().upsertResults(repaired)
    }
    suspend fun writeStandings(season: Int, kind: String, rankings: F1Rankings, originFetchedUtc: String) = db.transaction {
        val round = rankings.round ?: return@transaction
        val stamp = originFetchedUtc
        if (!incomingWins(stamp, db.sourceLog().time("f1:$season:$kind:$round"))) return@transaction
        db.f1().clearStandings(season, kind, round)
        db.f1().upsertStandings(rankings.entries.map { F1StandingRow(season, kind, round, it.position, it.name, it.team, it.points, it.detail, stamp, it.driverId, it.constructorId, it.grid, it.nationality) })
        db.sourceLog().upsert(listOf(SourceRow("f1:$season:$kind:$round", originFetchedUtc, "standings")))
    }

    // ---- F1 reads ----
    /** Only missing image identity fields; exact season/name/team match, no sporting facts changed. */
    suspend fun restoreStandingMediaIds(season: Int, kind: String, entries: List<RankingEntry>) = db.transaction {
        val identities = entries.groupBy { it.name to it.team }
        val repaired = db.f1().allStandings(season, kind).mapNotNull { row ->
            val source = identities[row.name to row.team]?.singleOrNull() ?: return@mapNotNull null
            if ((row.driverId != null && row.driverId != source.driverId) ||
                (row.constructorId != null && row.constructorId != source.constructorId) ||
                (row.nationality != null && row.nationality != source.nationality)) return@mapNotNull null
            row.copy(driverId = row.driverId ?: source.driverId, constructorId = row.constructorId ?: source.constructorId,
                nationality = row.nationality ?: source.nationality).takeIf { it != row }
        }
        db.f1().upsertStandings(repaired)
    }
    suspend fun restoreResultMediaIds(season: Int, round: Int, entries: List<RankingEntry>) = db.transaction {
        val identities = entries.groupBy { it.name to it.team }
        val repaired = db.f1().results(season, round).mapNotNull { row ->
            val source = identities[row.name to row.team]?.singleOrNull() ?: return@mapNotNull null
            if ((row.driverId != null && row.driverId != source.driverId) ||
                (row.constructorId != null && row.constructorId != source.constructorId) ||
                (row.nationality != null && row.nationality != source.nationality)) return@mapNotNull null
            row.copy(driverId = row.driverId ?: source.driverId, constructorId = row.constructorId ?: source.constructorId,
                nationality = row.nationality ?: source.nationality).takeIf { it != row }
        }
        db.f1().upsertResults(repaired)
    }
    suspend fun f1Races(season: Int): Pair<List<F1Race>, String?> = withContext(Dispatchers.IO) {
        db.transaction {
        val rows = db.f1().races(season)
        val sessions = db.f1().sessions(season).groupBy { it.round }
        rows.map { row ->
            val original = ArrayList<F1Session>(sessions[row.round].orEmpty().size)
            for (session in sessions[row.round].orEmpty()) {
                // Legacy rows also stored the race itself here; its real UTC time already lives in F1RaceRow.
                if (session.title != "正赛") original.add(F1Session(session.title, session.date, session.time))
            }
            original.sortWith(sessionOrder)
            F1Race(row.season, row.round, row.name, row.circuit, row.date, row.utcTime, row.country, original, row.circuitId)
        } to db.sourceLog().time("f1:$season")
        }
    }
    suspend fun f1Results(season: Int, round: Int): Pair<List<RankingEntry>, String?> = withContext(Dispatchers.IO) {
        val rows = db.f1().results(season, round)
        rows.map { RankingEntry(it.position, it.name, it.team, it.points, it.detail, it.grid, it.nationality, it.driverId, it.constructorId) } to db.sourceLog().time("f1:$season:$round")
    }
    suspend fun f1Standings(season: Int, kind: String, round: String?): Pair<F1Rankings, String?>? = withContext(Dispatchers.IO) {
        val resolved = round ?: db.f1().latestRound(season, kind) ?: return@withContext null
        val time = db.sourceLog().time("f1:$season:$kind:$resolved")
        // Legacy upserts could leave rows from an older, longer snapshot behind.
        val rows = db.f1().standings(season, kind, resolved).filter {
            time == null || parseProviderInstant(it.updatedAt) == parseProviderInstant(time)
        }
        if (rows.isEmpty()) null
        else F1Rankings(resolved, rows.map { RankingEntry(it.position, it.name, it.team, it.points, it.detail, it.grid, it.nationality, it.driverId, it.constructorId) }) to db.sourceLog().time("f1:$season:$kind:$resolved")
    }
    suspend fun teamName(teamId: Int): String? = withContext(Dispatchers.IO) { db.matches().homeName(teamId) ?: db.matches().awayName(teamId) }
    suspend fun sourceTime(url: String): String? = withContext(Dispatchers.IO) { db.sourceLog().time(url) }
    suspend fun calendarTimes(): Map<Int, String> = withContext(Dispatchers.IO) {
        db.sourceLog().byKind("calendar").mapNotNull { (url, time) ->
            url.substringAfter("f1:").substringBefore(':').toIntOrNull()?.let { it to time }
        }.toMap()
    }
}

private fun MatchRow.model() = FootballMatch(id = matchId, competition = competitionTitle, stage = stage, home = home, away = away,
    homeShort = homeShort, awayShort = awayShort, score = score, status = status, stadium = venue, date = date,
    kickoffUtc = kickoffUtc, lineupAvailable = false, eventsAvailable = false, statsAvailable = false, isDemo = false,
    source = "ESPN 公开数据 · 非官方接口", homeTeamId = homeTeamId, awayTeamId = awayTeamId, homeBadgeUrl = homeBadgeUrl,
    awayBadgeUrl = awayBadgeUrl, competitionId = competitionId, leg = leg, liveDetail = liveDetail)
