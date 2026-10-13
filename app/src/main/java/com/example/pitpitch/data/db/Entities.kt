package com.example.pitpitch.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Season fixtures; the newest updatedAt wins when day/season refreshes overwrite archive rows. */
@Entity(tableName = "matches", indices = [Index("competitionId", "season"), Index("updatedAt")])
data class MatchRow(
    @PrimaryKey val matchId: String,
    val competitionId: String,
    val competitionTitle: String,
    val season: Int,
    val stage: String,
    val date: String,
    val kickoffUtc: String?,
    val status: String,
    val score: String,
    val liveDetail: String?,
    val homeTeamId: Int?,
    val home: String,
    val homeShort: String,
    val homeBadgeUrl: String?,
    val awayTeamId: Int?,
    val away: String,
    val awayShort: String,
    val awayBadgeUrl: String?,
    val venue: String,
    val leg: Int?,
    val updatedAt: String,
)

@Entity(tableName = "standings", primaryKeys = ["competitionId", "season", "groupName", "position"],
    indices = [Index("competitionId", "season")])
data class StandingRow(
    val competitionId: String,
    val season: Int,
    val groupName: String,
    val position: String,
    val teamId: Int,
    val teamName: String,
    val played: String,
    val won: String,
    val drawn: String,
    val lost: String,
    val goalsFor: String,
    val goalsAgainst: String,
    val difference: String,
    val points: String,
    val deductions: String?,
    val updatedAt: String,
    val badgeUrl: String? = null, val providerNote: String? = null,
)

@Entity(tableName = "players", primaryKeys = ["playerId", "season", "competitionId", "teamId"],
    indices = [Index("competitionId", "season"), Index("teamId")])
data class PlayerRow(
    val playerId: String,
    val season: Int,
    val competitionId: String,
    val teamId: Int,
    val teamName: String,
    val name: String,
    val position: String,
    val jersey: String?,
    val appearances: String?,
    val goals: String?,
    val assists: String?,
    val photoUrl: String?,
    val updatedAt: String,
)

@Entity(tableName = "matchTeams", primaryKeys = ["matchId", "teamId"])
data class MatchTeamRow(val matchId: String, val teamId: Int, val formation: String?, val updatedAt: String)

@Entity(tableName = "matchLineups", primaryKeys = ["matchId", "teamId", "role", "sortIndex"], indices = [Index("matchId")])
data class LineupRow(
    val matchId: String,
    val teamId: Int,
    val role: String,
    val sortIndex: Int,
    val playerId: String,
    val playerName: String,
    val jersey: String?,
    val position: String,
    val photoUrl: String?,
    val goals: String?,
    val appearances: String?,
    val assists: String?,
    val updatedAt: String,
)

@Entity(tableName = "matchStats", primaryKeys = ["matchId", "label"], indices = [Index("matchId")])
data class StatRow(val matchId: String, val label: String, val home: String?, val away: String?, val updatedAt: String)

@Entity(tableName = "matchEvents", indices = [Index("matchId")])
data class EventRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val matchId: String,
    val minute: Int,
    val added: Int,
    val seq: Int,
    val label: String,
    val updatedAt: String,
    val richEvent: String? = null,
)

@Entity(tableName = "f1Races", primaryKeys = ["season", "round"])
data class F1RaceRow(val season: Int, val round: Int, val name: String, val circuit: String, val country: String,
                     val date: String, val utcTime: String?, val circuitId: String?, val updatedAt: String)

@Entity(tableName = "f1Sessions", primaryKeys = ["season", "round", "title"])
data class F1SessionRow(val season: Int, val round: Int, val title: String, val date: String, val time: String?, val updatedAt: String)

@Entity(tableName = "f1Results", primaryKeys = ["season", "round", "position"], indices = [Index("season", "round")])
data class F1ResultRow(val season: Int, val round: Int, val position: String, val name: String, val team: String,
                       val points: String, val detail: String, val updatedAt: String,
                       val driverId: String? = null, val constructorId: String? = null, val grid: String? = null, val nationality: String? = null)

@Entity(tableName = "f1Standings", primaryKeys = ["season", "kind", "round", "position"], indices = [Index("season", "kind")])
data class F1StandingRow(val season: Int, val kind: String, val round: String, val position: String,
                         val name: String, val team: String, val points: String, val detail: String, val updatedAt: String,
                       val driverId: String? = null, val constructorId: String? = null, val grid: String? = null, val nationality: String? = null)

/** Per-URL freshness bookkeeping: keeps "newer cache wins" and "cache may be stale" semantics. */
@Entity(tableName = "sourceLog")
data class SourceRow(@PrimaryKey val url: String, val fetchedUtc: String, val kind: String)

@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val key: String, val value: String)

data class Freshness(val matchId: String, val updatedAt: String)
data class UrlTime(val url: String, val fetchedUtc: String)
