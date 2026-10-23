package com.example.pitpitch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [MatchRow::class, StandingRow::class, PlayerRow::class, MatchTeamRow::class, LineupRow::class,
        StatRow::class, EventRow::class, F1RaceRow::class, F1SessionRow::class, F1ResultRow::class,
        F1StandingRow::class, SourceRow::class, MetaRow::class, LocalAssetRow::class,
        LocalDocumentRow::class, SourceSnapshotRow::class, FootballRoundRow::class],
    version = 4, exportSchema = false,
)
abstract class PitPitchDatabase : RoomDatabase() {
    abstract fun matches(): MatchDao
    abstract fun standings(): StandingDao
    abstract fun players(): PlayerDao
    abstract fun matchDetails(): MatchDetailDao
    abstract fun f1(): F1Dao
    abstract fun sourceLog(): SourceLogDao
    abstract fun meta(): MetaDao
    abstract fun localData(): LocalDataDao
    suspend fun <R> transaction(block: suspend () -> R): R = withTransaction { block() }

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                for ((table, columns) in listOf(
                    "standings" to listOf("badgeUrl", "providerNote"),
                    "matchEvents" to listOf("richEvent"),
                    "f1Results" to listOf("driverId", "constructorId", "grid", "nationality"),
                    "f1Standings" to listOf("driverId", "constructorId", "grid", "nationality"),
                )) for (column in columns) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` TEXT")
                // Calendar-year scoreboards contain the spring and autumn halves of different football seasons.
                db.execSQL("UPDATE matches SET updatedAt = COALESCE((SELECT MAX(fetchedUtc) FROM sourceLog WHERE url LIKE '%/soccer/' || matches.competitionId || '/scoreboard?dates=' || substr(matches.date, 1, 4) || '&%'), updatedAt)")
                db.execSQL("UPDATE players SET updatedAt = COALESCE((SELECT fetchedUtc FROM sourceLog WHERE url LIKE '%/soccer/' || players.competitionId || '/teams/' || (players.teamId - 1000000) || '/roster?season=' || players.season), updatedAt)")
                // Restore resource acquisition times when recorded, never the installation clock.
                db.execSQL("UPDATE standings SET updatedAt = COALESCE((SELECT fetchedUtc FROM sourceLog WHERE url = 'standings:' || standings.competitionId || ':' || standings.season), updatedAt)")
                db.execSQL("UPDATE f1Races SET updatedAt = COALESCE((SELECT fetchedUtc FROM sourceLog WHERE url = 'f1:' || f1Races.season), updatedAt)")
                db.execSQL("UPDATE f1Sessions SET updatedAt = COALESCE((SELECT fetchedUtc FROM sourceLog WHERE url = 'f1:' || f1Sessions.season), updatedAt)")
                db.execSQL("UPDATE f1Results SET updatedAt = COALESCE((SELECT fetchedUtc FROM sourceLog WHERE url = 'f1:' || f1Results.season || ':' || f1Results.round), updatedAt)")
                db.execSQL("UPDATE f1Standings SET updatedAt = COALESCE((SELECT fetchedUtc FROM sourceLog WHERE url = 'f1:' || f1Standings.season || ':' || f1Standings.kind || ':' || f1Standings.round), updatedAt)")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS localAssets (resourceKey TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, entityId TEXT, season INTEGER, assetPath TEXT NOT NULL, sourceUrl TEXT, sha256 TEXT NOT NULL, width INTEGER, height INTEGER, metadataJson TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_localAssets_kind_season_entityId ON localAssets(kind, season, entityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_localAssets_sourceUrl ON localAssets(sourceUrl)")
                db.execSQL("CREATE TABLE IF NOT EXISTS localDocuments (resourceKey TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL, sha256 TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sourceSnapshots (url TEXT NOT NULL, origin TEXT NOT NULL, body TEXT NOT NULL, fetchedUtc TEXT NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(url, origin))")
                db.execSQL("CREATE TABLE IF NOT EXISTS footballRounds (season INTEGER NOT NULL, matchId TEXT NOT NULL, competitionId TEXT NOT NULL, homeTeamId INTEGER NOT NULL, awayTeamId INTEGER NOT NULL, providerNote TEXT NOT NULL, number INTEGER NOT NULL, source TEXT NOT NULL, PRIMARY KEY(season, matchId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_footballRounds_competitionId_season ON footballRounds(competitionId, season)")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_matches_season ON matches(season)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_matches_homeTeamId ON matches(homeTeamId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_matches_awayTeamId ON matches(awayTeamId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_matches_status ON matches(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_players_competitionId_season_teamId ON players(competitionId, season, teamId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sourceLog_kind ON sourceLog(kind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sourceSnapshots_origin ON sourceSnapshots(origin)")
            }
        }
        const val SEED_VERSION = "5" // bump when bundled archive layout changes and rows must re-import
        @Volatile private var instance: PitPitchDatabase? = null
        fun build(context: Context): PitPitchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PitPitchDatabase::class.java, "pitpitch.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { instance = it }
        }
    }
}
