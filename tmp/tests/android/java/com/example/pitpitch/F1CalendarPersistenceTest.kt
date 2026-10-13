package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.F1Api
import com.example.pitpitch.data.SportsArchive
import com.example.pitpitch.data.db.F1SessionRow
import com.example.pitpitch.data.db.FootballStore
import com.example.pitpitch.data.db.PitPitchDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class F1CalendarPersistenceTest {
    @Test fun everyBundledCalendarRestoresOriginalSessionsWithoutAddingRaceOrReorderingPractices() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val archive = SportsArchive(context)
            val store = FootballStore(db)
            for (year in 2020..2026) {
                val original = archive.read("https://api.jolpi.ca/ergast/f1/$year.json?limit=100") { F1Api.parseSeason(it, year) }!!
                store.writeRaces(year, original.data, original.fetchedUtc)
                val restored = store.f1Races(year)
                assertEquals(original.data, restored.first)
                assertEquals(original.fetchedUtc, restored.second)
                // Old releases duplicated each race's real UTC time as a derived session row.
                val race = original.data.first()
                db.f1().upsertSessions(listOf(F1SessionRow(year, race.round, "正赛", race.date, race.utcTime, original.fetchedUtc)))
                assertEquals(original.data, store.f1Races(year).first)
                // The normal upgrade/domain pass removes only those derived duplicates, retaining all source sessions.
                store.writeRaces(year, original.data, original.fetchedUtc)
                assertEquals(original.data.flatMap { r -> r.sessions.map { r.round to it.title } }.toSet(),
                    db.f1().sessions(year).map { it.round to it.title }.toSet())
                assertEquals(original.data, store.f1Races(year).first)
            }
        } finally { db.close() }
    }
}
