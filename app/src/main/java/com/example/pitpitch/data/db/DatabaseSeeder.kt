package com.example.pitpitch.data.db

import com.example.pitpitch.data.EspnFootballAdapter
import com.example.pitpitch.data.F1Api
import com.example.pitpitch.data.JolpicaAdapter
import com.example.pitpitch.data.SportsArchive
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.data.footballArchiveYears
import com.example.pitpitch.data.f1ArchiveYears
import com.example.pitpitch.data.parseProviderInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK bootstrap merges only missing/invalid primary keys; valid device facts and clocks win.
 * Explicit network-origin resources keep the store's normal newer-effective replacement rule.
 */
class DatabaseSeeder(private val db: PitPitchDatabase, private val store: FootballStore, private val archive: SportsArchive, private val client: com.example.pitpitch.data.JsonClient? = null, private val raceCache: com.example.pitpitch.data.RaceCache? = null) {
    private val sources = if (archive.usesDatabase(db)) archive else SportsArchive(archive.context, db)
    private val provenBundledClocks = mutableMapOf<String, String>()
    companion object {
        const val SEED_VERSION = "${PitPitchDatabase.SEED_VERSION}:local-first-3"
        /** Bumped when stored provider states are remapped; the device then re-derives matches from its own responses. */
        const val MATCH_PARSE_VERSION = "3"
    }
    suspend fun seedIfNeeded(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        provenBundledClocks.clear()
        LocalDataImporter(archive.context, db).importIfNeeded()
        val revision = requireNotNull(db.meta().get("localDataRevision"))
        val failures = mutableListOf<String>()
        // A corrected parser must re-derive stored fixtures whichever seeding path runs below; the
        // helper keeps its own version guard, so this is a no-op once the current parse version is applied.
        reparseStartedPendingScopes()
        if (db.meta().get("seedVersion") == SEED_VERSION && db.meta().get("seedDataRevision") == revision) {
            restoreCompleteResults()
            restoreF1MediaIds(failures)
            db.meta().put(MetaRow("seedFailures", failures.size.toString()))
            return@withContext
        }
        val urls = archive.urls()
        require(urls.isNotEmpty()) { "Archive catalog is unavailable" }
        val teamNames = mutableMapOf<Int, String>()
        var done = 0
        fun progress(label: String) { done++; onProgress("$label（$done/${urls.size}）") }
        // Each resource is atomic. An incomplete pass remains retryable without discarding successful resources.
        suspend fun seed(url: String) = try {
            db.transaction {
                when {
                    "/scoreboard?dates=" in url -> seedSchedule(url, teamNames)
                    "/standings?season=" in url -> seedStandings(url)
                    "/roster?season=" in url -> seedRoster(url, teamNames)
                    "/ergast/f1/" in url -> seedF1(url)
                }
            }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failures += "$url: $e"; false
        }
        // Schedules first: they also provide team id → name for rosters.
        for ((url, _) in urls) if ("/scoreboard?dates=" in url) { seed(url); progress("赛程") }
        for ((url, _) in urls) if ("/standings?season=" in url) { seed(url); progress("积分") }
        for ((url, _) in urls) if ("/roster?season=" in url) { seed(url); progress("名单") }
        for ((url, _) in urls) if ("/ergast/f1/" in url) { seed(url); progress("F1") }
        restoreCompleteResults()
        restoreF1MediaIds(failures)
        db.meta().put(MetaRow("seedFailures", failures.size.toString()))
        if (failures.isEmpty()) db.transaction {
            db.meta().put(MetaRow("seedVersion", SEED_VERSION))
            db.meta().put(MetaRow("seedDataRevision", revision))
        }
    }
    /**
     * A stored provider state can predate a parser fix. Re-derive only the affected seasons from the
     * responses already on the device, so every fact comes from its own source without any network access.
     * Each response keeps its own acquisition time, so this never outranks newer data.
     */
    private suspend fun reparseStartedPendingScopes() {
        if (db.meta().get("matchParseVersion") == MATCH_PARSE_VERSION) return
        val now = java.time.Instant.now()
        val today = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val scopes = db.matches().startedPendingScopes(now.toString(), today).map { it.competitionId to it.season }.toSet()
        // A scope that cannot be re-derived is retried on the next launch instead of being marked done.
        val skipped = mutableSetOf<Pair<String, Int>>()
        for (url in db.localData().snapshotUrls()) {
            if ("/scoreboard?dates=" !in url) continue
            val leagueId = url.substringAfter("soccer/").substringBefore("/")
            val league = footballCompetitions.firstOrNull { it.id == leagueId } ?: continue
            // Season scoreboards use a calendar year; daily overlays use YYYYMMDD. Both name their season by the leading year.
            val dates = url.substringAfter("dates=").substringBefore("&")
            val year = dates.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }?.toIntOrNull() ?: continue
            for (season in listOf(year - 1, year)) {
                val scope = leagueId to season
                if (scope !in scopes) continue
                val snapshot = try {
                    archive.read(url) { EspnFootballAdapter.matches(it, league, season) }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    null
                }
                if (snapshot == null) { skipped += scope; continue }
                store.writeMatches(snapshot.data, league.id, league.title, season, snapshot.fetchedUtc,
                    url.takeIf { snapshot.cached })
            }
        }
        if (skipped.isEmpty()) db.meta().put(MetaRow("matchParseVersion", MATCH_PARSE_VERSION))
        else android.util.Log.w("PitPitchSeed", "pending-state reparse skipped $skipped; retrying next launch")
    }
    private suspend fun restoreCompleteResults() {
        if (db.meta().get("completeF1ResultsVersion") == "2") return
        db.transaction {
            if (db.meta().get("completeF1ResultsVersion") == "2") return@transaction
            for (url in archive.urls().map { it.first }) {
                val match = Regex("/ergast/f1/(\\d+)/(\\d+)/results\\.json").find(url) ?: continue
                val year = match.groupValues[1].toInt()
                val round = match.groupValues[2].toInt()
                if (year !in f1ArchiveYears()) continue
                val source = seedSource(url) { JolpicaAdapter.resultsForRound(it, year, round) } ?: continue
                store.repairLegacyResults(year, round, source.data, source.fetchedUtc)
                seedResults(year, round, source)
            }
            db.meta().put(MetaRow("completeF1ResultsVersion", "2"))
        }
    }
    private suspend fun restoreF1MediaIds(failures: MutableList<String>) {
        if (db.meta().get("f1MediaBindingVersion") == "1") return
        var incomplete = false
        for (url in archive.urls().map { it.first }.filter { "/ergast/f1/" in it }) {
            val rest = url.substringAfter("/ergast/f1/")
            val season = rest.substringBefore('/').substringBefore('.').toIntOrNull() ?: continue
            if (season !in f1ArchiveYears()) continue
            try {
                db.transaction {
                    when {
                        "driverstandings" in rest || "constructorstandings" in rest -> {
                            val constructors = "constructorstandings" in rest
                            val source = requireNotNull(archive.read(url) { JolpicaAdapter.standings(it, constructors, season) }) { "Unreadable archive resource: $url" }
                            store.restoreStandingMediaIds(season, if (constructors) "constructor" else "driver", source.data.entries)
                        }
                        "/results.json" in rest && "offset" !in rest -> {
                            val round = rest.substringAfter('/').substringBefore('/').toIntOrNull() ?: return@transaction
                            val source = requireNotNull(archive.read(url) { JolpicaAdapter.results(it) }) { "Unreadable archive resource: $url" }
                            store.restoreResultMediaIds(season, round, source.data)
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                incomplete = true
                failures += "$url: $failure"
            }
        }
        if (!incomplete) db.meta().put(MetaRow("f1MediaBindingVersion", "1"))
    }
    private suspend fun seedSchedule(url: String, teamNames: MutableMap<Int, String>) {
        val leagueId = url.substringAfter("soccer/").substringBefore("/")
        val league = requireNotNull(footballCompetitions.firstOrNull { it.id == leagueId }) { "Unknown league: $url" }
        val calendarYear = requireNotNull(url.substringAfter("dates=").substringBefore("&").toIntOrNull()) { "Invalid season: $url" }
        // A calendar-year scoreboard holds seasons Y-1 (spring half) and Y (autumn half).
        for (season in listOf(calendarYear - 1, calendarYear)) {
            if (season !in footballArchiveYears()) continue
            val parse = { body: String -> EspnFootballAdapter.matches(body, league, season) to EspnFootballAdapter.timeline(body, league, season) }
            val snapshot = requireNotNull(seedSource(url, parse)) { "Unreadable archive resource: $url" }
            if (!snapshot.cached && !canBootstrap(url, url)) continue
            val existing = db.matches().freshness(snapshot.data.first.map { it.id }).associate { it.matchId to it.updatedAt }
            val incoming = if (snapshot.cached) snapshot.data.first else snapshot.data.first.filter { !validTime(existing[it.id]) }
            if (incoming.isNotEmpty()) store.writeMatches(incoming, league.id, league.title, season, snapshot.fetchedUtc,
                url.takeIf { snapshot.cached || !validTime(db.sourceLog().time(url)) })
            snapshot.data.first.forEach { match ->
                match.homeTeamId?.let { teamNames.putIfAbsent(it, match.home) }
                match.awayTeamId?.let { teamNames.putIfAbsent(it, match.away) }
            }
            snapshot.data.second.forEach { (matchId, events) ->
                val retained = validTime(db.sourceLog().time("timeline:$matchId")) ||
                    db.matchDetails().events(matchId).any { validTime(it.updatedAt) }
                if (snapshot.cached || !retained) store.writeTimeline(matchId, events, snapshot.fetchedUtc,
                    url.takeIf { snapshot.cached || !validTime(db.sourceLog().time(url)) })
            }
        }
    }

    private suspend fun seedStandings(url: String) {
        val leagueId = url.substringAfter("soccer/").substringBefore("/")
        val season = requireNotNull(url.substringAfter("season=").toIntOrNull()) { "Invalid season: $url" }
        if (season !in footballArchiveYears()) return
        val tables = requireNotNull(seedSource(url) { EspnFootballAdapter.tables(it, season) }) { "Unreadable archive resource: $url" }
        val supplemented = archive.tableNotes(url, season, tables.data)
        if (tables.cached) store.writeStandings(leagueId, season, supplemented, tables.fetchedUtc)
        else {
            if (!canBootstrap(url, "standings:$leagueId:$season")) return
            val existing = db.standings().byCompetition(leagueId, season).filter { validTime(it.updatedAt) }
            val keys = existing.map { it.groupName to it.position }.toSet()
            val identities = existing.map { it.teamId }.toSet()
            db.standings().upsert(supplemented.flatMap { table -> table.entries.filter {
                (table.name to it.position) !in keys && it.team.id !in identities
            }.map {
                StandingRow(leagueId, season, table.name, it.position, it.team.id, it.team.name,
                    it.played, it.won, it.drawn, it.lost, it.goalsFor, it.goalsAgainst, it.difference,
                    it.points, it.deductions, tables.fetchedUtc, it.team.badgeUrl, it.providerNote)
            } })
            seedClock("standings:$leagueId:$season", tables.fetchedUtc, "standings")
        }
    }

    private suspend fun seedRoster(url: String, teamNames: Map<Int, String>) {
        val leagueId = url.substringAfter("soccer/").substringBefore("/")
        val season = requireNotNull(url.substringAfter("season=").toIntOrNull()) { "Invalid season: $url" }
        if (season !in footballArchiveYears()) return
        val club = requireNotNull(url.substringAfter("/teams/").substringBefore("/").toIntOrNull()) { "Invalid team: $url" }
        val teamId = club + 1_000_000
        val squad = requireNotNull(seedSource(url) { EspnFootballAdapter.squad(it, season) }) { "Unreadable archive resource: $url" }
        val teamName = teamNames[teamId] ?: db.matches().homeName(teamId) ?: db.matches().awayName(teamId) ?: "球队 $club"
        if (squad.cached) store.writeSquad(leagueId, season, teamId, teamName, squad.data, squad.fetchedUtc)
        else {
            if (!canBootstrap(url, "squad:$leagueId:$teamId:$season", url)) return
            val rows = db.players().byTeam(leagueId, season, teamId)
            if (rows.isEmpty() && validTime(db.sourceLog().time("squad:$leagueId:$teamId:$season"))) return
            val existing = rows.filter { validTime(it.updatedAt) }.map { it.playerId }.toSet()
            db.players().upsert(squad.data.players.filter { it.id !in existing }.map {
                PlayerRow(it.id, season, leagueId, teamId, teamName, it.name, it.position, it.jersey,
                    it.appearances, it.goals, it.assists, it.photoUrl, squad.fetchedUtc)
            })
            seedClock("squad:$leagueId:$teamId:$season", squad.fetchedUtc, "squad")
            seedClock(url, squad.fetchedUtc, "roster")
        }
    }

    private suspend fun seedF1(url: String) {
        val rest = url.substringAfter("/ergast/f1/")
        val season = requireNotNull(rest.substringBefore('/').substringBefore('.').toIntOrNull()) { "Invalid season: $url" }
        if (season !in f1ArchiveYears()) return
        when {
            rest == "$season.json?limit=100" || rest.startsWith("$season.json") -> {
                val races = requireNotNull(com.example.pitpitch.data.preferredLocalSnapshot(seedSource(url) { F1Api.parseSeason(it, season) }, raceCache?.snapshot(season))) { "Unreadable archive resource: $url" }
                if (races.cached) store.writeRaces(season, races.data, races.fetchedUtc)
                else {
                    if (!canBootstrap(url, "f1:$season")) return
                    val rows = db.f1().races(season)
                    if (rows.isEmpty() && validTime(db.sourceLog().time("f1:$season"))) return
                    val existing = rows.filter { validTime(it.updatedAt) }.map { it.round }.toSet()
                    val sessions = db.f1().sessions(season).filter { validTime(it.updatedAt) }.map { it.round to it.title }.toSet()
                    db.f1().upsertRaces(races.data.filter { it.round !in existing }.map {
                        F1RaceRow(season, it.round, it.name, it.circuit, it.country, it.date, it.utcTime, it.circuitId, races.fetchedUtc)
                    })
                    db.f1().upsertSessions(races.data.flatMap { race ->
                        race.sessions.filter { (race.round to it.title) !in sessions }.map {
                            F1SessionRow(season, race.round, it.title, it.date, it.time, races.fetchedUtc)
                        }
                    })
                    seedClock("f1:$season", races.fetchedUtc, "calendar")
                }
            }
            "driverstandings" in url -> {
                val rankings = requireNotNull(seedSource(url) { JolpicaAdapter.standings(it, false, season) }) { "Unreadable archive resource: $url" }
                seedRankings(season, "driver", rankings)
            }
            "constructorstandings" in url -> {
                val rankings = requireNotNull(seedSource(url) { JolpicaAdapter.standings(it, true, season) }) { "Unreadable archive resource: $url" }
                seedRankings(season, "constructor", rankings)
            }
            "/results.json" in url && "offset" !in url -> {
                val round = requireNotNull(rest.substringAfter('/').substringBefore('/').toIntOrNull()) { "Invalid round: $url" }
                val results = requireNotNull(seedSource(url) { JolpicaAdapter.resultsForRound(it, season, round) }) { "Unreadable archive resource: $url" }
                seedResults(season, round, results)
            }
        }
    }

    private fun validTime(time: String?) = time?.let(::parseProviderInstant) != null

    private fun <T> seedSource(url: String, parse: (String) -> T): com.example.pitpitch.data.RemoteSnapshot<T>? {
        val resolved = sources.readWithOrigin(url, parse)
        val local = resolved?.first ?: client?.archived(url, parse)
        provenBundledClocks.remove(url)
        // A complete network resource is authoritative, including intentional removals.
        if (local?.cached == true) return local
        if (resolved?.second == "bundled") {
            provenBundledClocks[url] = requireNotNull(parseProviderInstant(resolved.first.fetchedUtc))
            val digest = db.localData().snapshotMetadata(url, "bundled")?.sha256
            if (digest != null && digest == sources.packagedDigest(url)) return local
        } else if (resolved?.second == "apk") return local
        return sources.readPackaged(url, parse) ?: local
    }

    /** Unverifiable legacy complete resources cannot safely be unioned with an older APK. */
    private suspend fun canBootstrap(url: String, vararg keys: String): Boolean {
        val bundledTime = provenBundledClocks[url]
        for (key in keys) {
            val existingTime = db.sourceLog().time(key)?.let(::parseProviderInstant) ?: continue
            if (existingTime != bundledTime) return false
        }
        return true
    }

    private suspend fun seedClock(key: String, time: String, kind: String) {
        if (!validTime(db.sourceLog().time(key))) db.sourceLog().upsert(listOf(SourceRow(key, time, kind)))
    }

    private suspend fun seedResults(season: Int, round: Int, source: com.example.pitpitch.data.RemoteSnapshot<List<com.example.pitpitch.data.RankingEntry>>) {
        if (source.cached) { store.writeResults(season, round, source.data, source.fetchedUtc); return }
        val url = "https://api.jolpi.ca/ergast/f1/$season/$round/results.json?limit=100"
        if (!canBootstrap(url, "f1:$season:$round")) return
        store.repairLegacyResults(season, round, source.data, source.fetchedUtc)
        val rows = db.f1().results(season, round)
        if (rows.isEmpty() && validTime(db.sourceLog().time("f1:$season:$round"))) return
        val existing = rows.filter { validTime(it.updatedAt) }
        val positions = existing.map { it.position }.toSet()
        val identities = existing.map { it.driverId ?: it.name }.toSet()
        val names = existing.map { it.name }.toSet()
        db.f1().upsertResults(source.data.filter {
            it.position !in positions && (it.driverId ?: it.name) !in identities && it.name !in names
        }.map {
            F1ResultRow(season, round, it.position, it.name, it.team, it.points, it.detail, source.fetchedUtc,
                it.driverId, it.constructorId, it.grid, it.nationality)
        })
        seedClock("f1:$season:$round", source.fetchedUtc, "results")
    }

    private suspend fun seedRankings(season: Int, kind: String, source: com.example.pitpitch.data.RemoteSnapshot<com.example.pitpitch.data.F1Rankings>) {
        if (source.cached) { store.writeStandings(season, kind, source.data, source.fetchedUtc); return }
        val round = source.data.round ?: return
        val endpoint = if (kind == "driver") "driverstandings" else "constructorstandings"
        if (!canBootstrap("https://api.jolpi.ca/ergast/f1/$season/$endpoint.json?limit=100", "f1:$season:$kind:$round")) return
        val rows = db.f1().standings(season, kind, round)
        if (rows.isEmpty() && validTime(db.sourceLog().time("f1:$season:$kind:$round"))) return
        val existing = rows.filter { validTime(it.updatedAt) }
        val positions = existing.map { it.position }.toSet()
        val identities = existing.map { if (kind == "driver") it.driverId ?: it.name else it.constructorId ?: it.name }.toSet()
        val names = existing.map { it.name }.toSet()
        db.f1().upsertStandings(source.data.entries.filter {
            val id = if (kind == "driver") it.driverId ?: it.name else it.constructorId ?: it.name
            it.position !in positions && id !in identities && it.name !in names
        }.map {
            F1StandingRow(season, kind, round, it.position, it.name, it.team, it.points, it.detail, source.fetchedUtc,
                it.driverId, it.constructorId, it.grid, it.nationality)
        })
        seedClock("f1:$season:$kind:$round", source.fetchedUtc, "standings")
    }
}
