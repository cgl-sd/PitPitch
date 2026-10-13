package com.example.pitpitch

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.db.PitPitchDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class LegacyDatabaseMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val imported = "2026-10-10T00:00:00Z"
    private val seasonTime = "2026-10-01T01:00:00Z"
    private val otherSeasonTime = "2026-10-02T02:00:00Z"
    private val tables = listOf("matches", "standings", "players", "matchTeams", "matchLineups", "matchStats",
        "matchEvents", "f1Races", "f1Sessions", "f1Results", "f1Standings", "sourceLog", "meta")

    @Test fun versionOneUpgradePreservesAllFactsAndUsesScoreboardCalendarYearWithoutGuessingMissingSource() = runBlocking {
        val name = "migration-v1-${UUID.randomUUID()}.db"
        val old = context.openOrCreateDatabase(name, 0, null)
        val before = try {
            val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("pitpitch-v1-schema.sql")
                .bufferedReader().use { it.readText() }
            schema.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .split(';').filter { it.isNotBlank() }.forEach(old::execSQL)
            for (table in tables) insertLegacyRow(old, table)
            old.execSQL("INSERT INTO matches SELECT 'without-resource', 'esp.1', competitionTitle, season, stage, date, kickoffUtc, status, score, liveDetail, homeTeamId, home, homeShort, homeBadgeUrl, awayTeamId, away, awayShort, awayBadgeUrl, venue, leg, updatedAt FROM matches")
            for ((url, stamp) in listOf(
                "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates=2020&limit=1000" to seasonTime,
                "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard?dates=2021&limit=1000" to otherSeasonTime,
                "https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard?dates=2020&limit=1000" to otherSeasonTime,
                "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/teams/360/roster?season=2020" to seasonTime,
                "standings:eng.1:2020" to seasonTime,
                "f1:2020" to seasonTime,
                "f1:2020:1" to seasonTime,
                "f1:2020:driver:1" to seasonTime,
            )) old.execSQL("INSERT INTO sourceLog VALUES (?, ?, ?)", arrayOf(url, stamp, "archive"))
            old.version = 1
            tables.associateWith { rows(old, it) }
        } catch (failure: Exception) {
            old.close()
            context.deleteDatabase(name)
            throw failure
        } finally { if (old.isOpen) old.close() }

        val db = Room.databaseBuilder(context, PitPitchDatabase::class.java, name)
            .addMigrations(PitPitchDatabase.MIGRATION_1_2, PitPitchDatabase.MIGRATION_2_3).build()
        try {
            val sqlite = db.openHelper.readableDatabase
            assertEquals(3, sqlite.version)
            for (table in tables) {
                val expected = before.getValue(table).map { row ->
                    when {
                        table == "matches" && row["competitionId"] == "eng.1" -> row + ("updatedAt" to otherSeasonTime)
                        table in setOf("players", "standings", "f1Races", "f1Sessions", "f1Results", "f1Standings") -> row + ("updatedAt" to seasonTime)
                        else -> row
                    }
                }
                val columns = before.getValue(table).first().keys.toList()
                val actual = sqlite.query("SELECT ${columns.joinToString(",") { "`$it`" }} FROM `$table` ORDER BY `${columns.first()}`").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(columns.associateWith { key ->
                            val i = cursor.getColumnIndexOrThrow(key)
                            if (cursor.isNull(i)) null else cursor.getString(i)
                        })
                    }
                }
                assertEquals("All v1 fields retained in $table", expected, actual)
            }
            sqlite.query("SELECT badgeUrl, providerNote FROM standings").use { cursor ->
                cursor.moveToFirst(); assertNull(cursor.getString(0)); assertNull(cursor.getString(1))
            }
            sqlite.query("SELECT richEvent FROM matchEvents").use { cursor -> cursor.moveToFirst(); assertNull(cursor.getString(0)) }
            for (table in listOf("f1Results", "f1Standings")) sqlite.query("SELECT driverId, constructorId, grid, nationality FROM $table").use { cursor ->
                cursor.moveToFirst(); repeat(4) { assertNull(cursor.getString(it)) }
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun insertLegacyRow(db: SQLiteDatabase, table: String) {
        val values = ContentValues()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                val type = cursor.getString(2)
                val value = when (column) {
                    "season" -> "2020"
                    "teamId", "homeTeamId" -> "1000360"
                    "awayTeamId" -> "1000361"
                    "matchId" -> "legacy-match"
                    "competitionId" -> "eng.1"
                    "date" -> "2021-02-21"
                    "kickoffUtc" -> "2021-02-21T19:00:00Z"
                    "updatedAt", "fetchedUtc" -> imported
                    "kind" -> "driver"
                    "round", "position" -> "1"
                    "home", "teamName" -> "Manchester United"
                    "away" -> "Newcastle United"
                    "score" -> "3–1"
                    "status" -> "FINISHED"
                    "formation" -> "4-2-3-1"
                    "playerName", "name" -> "Original English Full Name"
                    else -> if (type == "INTEGER") "1" else "原始 · $table/$column"
                }
                values.put(column, value)
            }
        }
        db.insertOrThrow(table, null, values)
    }

    private fun rows(db: SQLiteDatabase, table: String): List<Map<String, String?>> = db.rawQuery("SELECT * FROM `$table` ORDER BY 1", null).use { cursor ->
        val columns = cursor.columnNames.toList()
        buildList {
            while (cursor.moveToNext()) add(columns.associateWith { key ->
                val i = cursor.getColumnIndexOrThrow(key)
                if (cursor.isNull(i)) null else cursor.getString(i)
            })
        }
    }
}
