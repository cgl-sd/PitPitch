package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.F1QualifyingRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class F1CompleteDataTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun upgradedResultsRestoreEveryDriverIncludingAllRetirementsAndZeroPoints() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val archive = SportsArchive(context); val store = FootballStore(db)
            val url = "https://api.jolpi.ca/ergast/f1/2020/1/results.json?limit=100"
            val original = archive.read(url) { JolpicaAdapter.resultsForRound(it, 2020, 1) }!!
            val legacyTime = Instant.parse(original.fetchedUtc).plusSeconds(60).toString()
            val legacy = original.data.map { if (it.position.toInt() >= 14) it.copy(position = "R") else it }
            db.f1().upsertResults(legacy.map { F1ResultRow(2020, 1, it.position, it.name, it.team, it.points, it.detail, legacyTime) })
            db.sourceLog().upsert(listOf(SourceRow("f1:2020:1", legacyTime, "results")))
            assertTrue(store.f1Results(2020, 1).first.size < original.data.size)
            db.meta().put(MetaRow("seedVersion", DatabaseSeeder.SEED_VERSION))
            db.meta().put(MetaRow("seedDataRevision", context.assets.open("local-backend-revision.txt").bufferedReader().use { it.readText().trim() }))
            db.meta().put(MetaRow("f1MediaBindingVersion", "1"))
            DatabaseSeeder(db, store, archive).seedIfNeeded()
            var total = 0; var zeros = 0; var retired = 0
            for ((u, time) in archive.urls()) {
                val match = Regex("/f1/(\\d+)/(\\d+)/results\\.json").find(u) ?: continue
                val year = match.groupValues[1].toInt(); val round = match.groupValues[2].toInt()
                val expected = archive.read(u) { JolpicaAdapter.resultsForRound(it, year, round) }!!.data
                val actual = store.f1Results(year, round)
                assertEquals(expected, actual.first); assertEquals(if (year == 2020 && round == 1) legacyTime else time, actual.second)
                assertEquals(expected.size, expected.map { it.driverId }.distinct().size)
                total += expected.size; zeros += expected.count { it.points == "0" }
                val raw = JSONObject(archive.response(u)!!.first).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0).getJSONArray("Results")
                retired += raw.objects().count { it.getString("positionText") == "R" }
            }
            val repaired = db.f1().results(2020, 1)
            assertEquals(setOf(original.fetchedUtc, legacyTime), repaired.map { it.updatedAt }.toSet())
            assertTrue(total > 2900); assertTrue(zeros > 1400); assertTrue(retired > 300)
            val newer = Instant.parse(original.fetchedUtc).plusSeconds(120).toString()
            store.writeResults(2020, 1, original.data.take(1), newer)
            store.writeResults(2020, 1, original.data, original.fetchedUtc)
            assertEquals(original.data.take(1), store.f1Results(2020, 1).first)
            DatabaseSeeder(db, store, archive).seedIfNeeded()
            assertEquals(newer, store.f1Results(2020, 1).second)
        } finally { db.close() }
    }
    @Test fun lappedDriversAreNeverShownAsRetiredAndTheirRealPointsRemain() {
        val archive = SportsArchive(context)
        var lapped = 0; var onePoint = 0
        for ((url, _) in archive.urls()) {
            val match = Regex("/f1/(\\d+)/(\\d+)/results\\.json").find(url) ?: continue
            val rows = archive.read(url) { JolpicaAdapter.resultsForRound(it, match.groupValues[1].toInt(), match.groupValues[2].toInt()) }!!.data
            for (row in rows.filter { it.detail.substringBefore(" · ") == "Lapped" }) {
                val result = com.example.pitpitch.feature.raceResultLabel(row)
                assertNotEquals("退赛", result)
                assertEquals(row.detail.split(" · ").getOrNull(2) ?: "被套圈", result)
                assertNotNull(row.constructorId)
                lapped++; if (row.points == "1") onePoint++
            }
        }
        assertTrue(lapped > 0); assertTrue(onePoint > 0)
        val unknownGap = RankingEntry("10", "Driver", "Team", "1", "Lapped · 50 圈")
        assertEquals("被套圈", com.example.pitpitch.feature.raceResultLabel(unknownGap))
    }
    @Test fun chinaDnsDriversRemainCompleteWithAccurateLabelsAndZeroPoints() {
        val archive = SportsArchive(context)
        val url = "https://api.jolpi.ca/ergast/f1/2026/2/results.json?limit=100"
        val rows = archive.read(url) { JolpicaAdapter.resultsForRound(it, 2026, 2) }!!.data
        assertEquals(22, rows.size)
        val dns = rows.filter { it.detail.substringBefore(" · ") == "Did not start" }
        assertEquals(listOf("piastri", "norris", "bortoleto", "albon"), dns.map { it.driverId })
        assertEquals(listOf("19", "20", "21", "22"), dns.map { it.position })
        dns.forEach {
            assertEquals("0", it.points)
            assertEquals("未发车", com.example.pitpitch.feature.raceResultLabel(it))
            assertNotNull(it.constructorId)
        }
        val sample = dns.first()
        assertEquals("退出参赛", com.example.pitpitch.feature.raceResultLabel(sample.copy(detail = "Withdrew")))
        assertEquals("未获资格", com.example.pitpitch.feature.raceResultLabel(sample.copy(detail = "Did not qualify")))
        assertEquals("退赛", com.example.pitpitch.feature.raceResultLabel(rows.first { it.driverId == "alonso" }))
        assertEquals("1", rows.first { it.driverId == "colapinto" }.points)
    }
    @Test fun qualifyingArchiveKeepsEveryPublishedDriverAndMissingSegments() {
        val archive = SportsArchive(context); var races = 0; var drivers = 0; var missingQ3 = 0
        for (year in 2020..2026) {
            val calendar = archive.read("https://api.jolpi.ca/ergast/f1/$year.json?limit=100") { F1Api.parseSeason(it, year) }!!.data
            for (race in calendar) {
                val u = "https://api.jolpi.ca/ergast/f1/$year/${race.round}/qualifying.json?limit=100"
                val body = archive.response(u)?.first ?: continue
                val rows = F1QualifyingRepository.parse(body, race)
                assertEquals(rows.size, rows.map { it.driverId }.distinct().size)
                assertEquals(rows.map { it.position?.toIntOrNull() ?: Int.MAX_VALUE }.sorted(), rows.map { it.position?.toIntOrNull() ?: Int.MAX_VALUE })
                assertTrue(runCatching { F1QualifyingRepository.parse(body, race.copy(round = race.round + 1)) }.isFailure)
                races++; drivers += rows.size; missingQ3 += rows.count { it.q3 == null }
            }
        }
        assertEquals(148, races); assertEquals(2986, drivers); assertTrue(missingQ3 > 1000)
    }
}
