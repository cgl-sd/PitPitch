package com.example.pitpitch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao interface MatchDao {
    @Upsert suspend fun upsert(rows: List<MatchRow>)
    @Query("SELECT * FROM matches WHERE season = :season ORDER BY kickoffUtc IS NULL, kickoffUtc, date")
    suspend fun bySeason(season: Int): List<MatchRow>
    @Query("SELECT matchId, updatedAt FROM matches WHERE matchId IN (:ids)")
    suspend fun freshness(ids: List<String>): List<Freshness>
    @Query("SELECT MIN(updatedAt) FROM matches WHERE season = :season")
    suspend fun seasonTime(season: Int): String?
    @Query("SELECT home FROM matches WHERE homeTeamId = :teamId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun homeName(teamId: Int): String?
    @Query("SELECT away FROM matches WHERE awayTeamId = :teamId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun awayName(teamId: Int): String?
    /** Scopes whose stored provider state may predate a parser fix: pending fixtures that already started. */
    @Query("SELECT DISTINCT competitionId, season FROM matches WHERE status = '待赛' AND " +
        "((kickoffUtc IS NOT NULL AND kickoffUtc < :now) OR (kickoffUtc IS NULL AND date < :today))")
    suspend fun startedPendingScopes(now: String, today: String): List<PendingScope>
}

/** Competition/season pair used to re-derive matches from their own stored responses. */
data class PendingScope(val competitionId: String, val season: Int)

@Dao interface StandingDao {
    @Upsert suspend fun upsert(rows: List<StandingRow>)
    @Query("DELETE FROM standings WHERE competitionId = :competitionId AND season = :season")
    suspend fun clear(competitionId: String, season: Int)
    @Query("SELECT * FROM standings WHERE competitionId = :competitionId AND season = :season ORDER BY groupName, CAST(position AS INTEGER)")
    suspend fun byCompetition(competitionId: String, season: Int): List<StandingRow>
}

@Dao interface PlayerDao {
    @Upsert suspend fun upsert(rows: List<PlayerRow>)
    @Query("SELECT * FROM players WHERE competitionId = :competitionId AND season = :season")
    suspend fun bySeason(competitionId: String, season: Int): List<PlayerRow>
    @Query("SELECT * FROM players WHERE competitionId = :competitionId AND season = :season AND teamId = :teamId")
    suspend fun byTeam(competitionId: String, season: Int, teamId: Int): List<PlayerRow>
    @Query("SELECT COALESCE((SELECT fetchedUtc FROM sourceLog WHERE url = 'squad:' || :competitionId || ':' || :teamId || ':' || :season), MAX(updatedAt)) FROM players WHERE competitionId = :competitionId AND season = :season AND teamId = :teamId")
    suspend fun squadTime(competitionId: String, season: Int, teamId: Int): String?
    @Query("DELETE FROM players WHERE competitionId = :competitionId AND season = :season AND teamId = :teamId")
    suspend fun clearTeam(competitionId: String, season: Int, teamId: Int)
}

@Dao interface MatchDetailDao {
    @Upsert suspend fun upsertTeams(rows: List<MatchTeamRow>)
    @Upsert suspend fun upsertLineups(rows: List<LineupRow>)
    @Upsert suspend fun upsertStats(rows: List<StatRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEvents(rows: List<EventRow>)
    @Query("DELETE FROM matchTeams WHERE matchId = :matchId")
    suspend fun clearTeams(matchId: String)
    @Query("SELECT MAX(updatedAt) FROM (SELECT updatedAt FROM matchTeams WHERE matchId = :matchId UNION ALL SELECT updatedAt FROM matchLineups WHERE matchId = :matchId UNION ALL SELECT updatedAt FROM matchStats WHERE matchId = :matchId UNION ALL SELECT updatedAt FROM matchEvents WHERE matchId = :matchId AND richEvent IS NOT NULL)")
    suspend fun detailTime(matchId: String): String?
    @Query("DELETE FROM matchLineups WHERE matchId = :matchId")
    suspend fun clearLineups(matchId: String)
    @Query("DELETE FROM matchStats WHERE matchId = :matchId")
    suspend fun clearStats(matchId: String)
    @Query("DELETE FROM matchEvents WHERE matchId = :matchId")
    suspend fun clearEvents(matchId: String)
    @Query("SELECT * FROM matchTeams WHERE matchId = :matchId")
    suspend fun teams(matchId: String): List<MatchTeamRow>
    @Query("SELECT * FROM matchLineups WHERE matchId = :matchId ORDER BY role, sortIndex")
    suspend fun lineups(matchId: String): List<LineupRow>
    @Query("SELECT * FROM matchStats WHERE matchId = :matchId")
    suspend fun stats(matchId: String): List<StatRow>
    @Query("SELECT * FROM matchEvents WHERE matchId = :matchId ORDER BY minute, added, seq")
    suspend fun events(matchId: String): List<EventRow>
    @Query("SELECT * FROM matchTeams WHERE matchId IN (:matchIds)")
    suspend fun teamsFor(matchIds: List<String>): List<MatchTeamRow>
    @Query("SELECT * FROM matchLineups WHERE matchId IN (:matchIds) ORDER BY role, sortIndex")
    suspend fun lineupsFor(matchIds: List<String>): List<LineupRow>
    @Query("SELECT * FROM matchStats WHERE matchId IN (:matchIds)")
    suspend fun statsFor(matchIds: List<String>): List<StatRow>
    @Query("SELECT * FROM matchEvents WHERE matchId IN (:matchIds) ORDER BY minute, added, seq")
    suspend fun eventsFor(matchIds: List<String>): List<EventRow>
    @Query("SELECT matchId, MAX(updatedAt) AS updatedAt FROM (SELECT matchId, updatedAt FROM matchTeams WHERE matchId IN (:matchIds) UNION ALL SELECT matchId, updatedAt FROM matchLineups WHERE matchId IN (:matchIds) UNION ALL SELECT matchId, updatedAt FROM matchStats WHERE matchId IN (:matchIds) UNION ALL SELECT matchId, updatedAt FROM matchEvents WHERE matchId IN (:matchIds) AND richEvent IS NOT NULL) GROUP BY matchId")
    suspend fun detailTimes(matchIds: List<String>): List<MatchDetailTime>
}

@Dao interface F1Dao {
    @Query("DELETE FROM f1Standings WHERE season = :season AND kind = :kind AND round = :round")
    suspend fun clearStandings(season: Int, kind: String, round: String)
    @Query("DELETE FROM f1Races WHERE season = :season") suspend fun clearRaces(season: Int)
    @Query("DELETE FROM f1Sessions WHERE season = :season") suspend fun clearSessions(season: Int)

    @Upsert suspend fun upsertRaces(rows: List<F1RaceRow>)
    @Upsert suspend fun upsertSessions(rows: List<F1SessionRow>)
    @Query("DELETE FROM f1Results WHERE season = :season AND round = :round")
    suspend fun clearResults(season: Int, round: Int)
    @Upsert suspend fun upsertResults(rows: List<F1ResultRow>)
    @Upsert suspend fun upsertStandings(rows: List<F1StandingRow>)
    @Query("SELECT * FROM f1Races WHERE season = :season ORDER BY round")
    suspend fun races(season: Int): List<F1RaceRow>
    @Query("SELECT * FROM f1Sessions WHERE season = :season")
    suspend fun sessions(season: Int): List<F1SessionRow>
    @Query("SELECT * FROM f1Results WHERE season = :season AND round = :round ORDER BY CAST(position AS INTEGER)")
    suspend fun results(season: Int, round: Int): List<F1ResultRow>
    @Query("SELECT * FROM f1Standings WHERE season = :season AND kind = :kind AND round = :round ORDER BY CAST(position AS INTEGER)")
    suspend fun standings(season: Int, kind: String, round: String): List<F1StandingRow>
    @Query("SELECT * FROM f1Standings WHERE season = :season AND kind = :kind")
    suspend fun allStandings(season: Int, kind: String): List<F1StandingRow>
    @Query("SELECT MAX(CAST(round AS INTEGER)) FROM f1Standings WHERE season = :season AND kind = :kind")
    suspend fun latestRound(season: Int, kind: String): String?
    @Query("SELECT COUNT(*) FROM f1Races")
    suspend fun raceCount(): Int
}

data class MatchDetailTime(val matchId: String, val updatedAt: String)

@Dao interface SourceLogDao {
    @Upsert suspend fun upsert(rows: List<SourceRow>)
    @Query("SELECT fetchedUtc FROM sourceLog WHERE url IN (:urls)")
    suspend fun times(urls: List<String>): List<String>
    @Query("SELECT fetchedUtc FROM sourceLog WHERE url = :url")
    suspend fun time(url: String): String?
    @Query("SELECT url, fetchedUtc FROM sourceLog WHERE kind = :kind")
    suspend fun byKind(kind: String): List<UrlTime>
    @Query("SELECT url, fetchedUtc FROM sourceLog WHERE url IN (:urls)")
    suspend fun urlTimes(urls: List<String>): List<UrlTime>
}

@Dao interface MetaDao {
    @Upsert suspend fun put(row: MetaRow)
    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun get(key: String): String?
}
