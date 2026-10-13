package com.example.pitpitch

import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EuropeanArchiveTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun allSevenCompetitionsFrom2020HaveFixturesAndProviderTables() = runBlocking {
        val archive = SportsArchive(context)
        val repo = EuropeanFootballRepository(JsonClient(archive = archive), archive)
        for (year in 2020..2026) {
            val season = repo.season(year)
            assertTrue("$year fixture errors ${season.errors}", season.errors.isEmpty())
            assertTrue(season.matches.all { !it.isDemo && it.id.startsWith("fb-espn-") })
            assertEquals(season.matches.size, season.matches.map { it.id }.toSet().size)
            for (league in footballCompetitions) {
                assertTrue("${league.id} $year missing fixtures", season.matches.any { it.competitionId == league.id })
                assertFalse("${league.id} $year missing standings", season.tables[league.id]?.data.isNullOrEmpty())
                if (league.european) {
                    val size = season.tables[league.id]!!.data.sumOf { it.entries.size }
                    assertEquals("European table $year", if (year >= 2024) 36 else if (league.id == "uefa.europa" && year < 2021) 48 else 32, size)
                }
            }
        }
    }
    @Test fun historicalSquadsAreDifferentAndUnverifiedCoachIsExcluded() {
        val archive = SportsArchive(context)
        val base = "${EspnFootballAdapter.SITE}eng.1/teams/359/roster?season="
        val old = archive.read(base + "2020") { EspnFootballAdapter.squad(it, 2020) }!!.data
        val newer = archive.read(base + "2025") { EspnFootballAdapter.squad(it, 2025) }!!.data
        assertTrue(old.players.any { it.name.contains("Aubameyang") })
        assertTrue(newer.players.any { it.name.contains("Saka") })
        assertNotEquals(old.players.map { it.id }.toSet(), newer.players.map { it.id }.toSet())
        assertNull(old.coach); assertNull(newer.coach)
        assertTrue(newer.players.any { it.goals != null })
        val noStats = JSONObject("""{"season":{"year":2025},"athletes":[{"id":"1","displayName":"Missing statistics","position":{"name":"Forward"}}],"coach":[{"firstName":"Stale","lastName":"Coach"}]}""")
        val missing = EspnFootballAdapter.squad(noStats.toString(), 2025)
        assertNull(missing.players.single().goals)
        assertEquals("前锋", missing.players.single().position)
        assertNull(missing.coach)
        try { EspnFootballAdapter.squad(noStats.toString(), 2020); fail("Different season accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun matchLineupsValidateEventAndTeamIdentityAndKeepMissingValues() {
        val body = InstrumentationRegistry.getInstrumentation().context.assets.open("espn-match-detail.json").bufferedReader().use { it.readText() }
        val match = sampleMatches[0].copy(id = "fb-espn-eng.1-704467", isDemo = false, competitionId = "eng.1", homeTeamId = 1000337, awayTeamId = 1000359)
        val detail = EspnMatchAdapter.detail(body, match)
        assertEquals(2, detail.lineups.size)
        assertTrue(detail.lineups.all { it.starters.size == 11 && it.substitutes.size == 9 })
        assertTrue(detail.lineups[1].starters.any { it.name == "David Raya" })
        assertEquals("4-3-3", detail.lineups[1].formation)
        assertTrue(detail.statistics.any { it.label == "球队成功抢断" && it.home == "7" })
        try { EspnMatchAdapter.detail(body, match.copy(homeTeamId = 1000001)); fail("Wrong teams accepted") } catch (_: IllegalArgumentException) {}
        try { EspnMatchAdapter.detail(body, match.copy(id = "fb-espn-eng.1-999")); fail("Wrong event accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun f1From2020IncludesAllPublishedResultsAndCircuitIds() {
        val archive = SportsArchive(context)
        for (year in 2020..2026) {
            val races = archive.read("https://api.jolpi.ca/ergast/f1/$year.json?limit=100") { F1Api.parseSeason(it, year) }!!.data
            assertTrue(races.isNotEmpty()); assertTrue(races.all { !it.circuitId.isNullOrBlank() })
            val drivers = archive.read("https://api.jolpi.ca/ergast/f1/$year/driverstandings.json?limit=100") { JolpicaAdapter.standings(it, false, year) }!!.data
            assertFalse(drivers.entries.isEmpty())
            val cutoff = drivers.round!!.toInt()
            for (race in races.filter { it.round <= cutoff }) {
                val results = archive.read("https://api.jolpi.ca/ergast/f1/$year/${race.round}/results.json?limit=100", JolpicaAdapter::results)!!.data
                // Some published classifications contain 19 entries after exclusions.
                assertTrue("$year/${race.round} missing classification", results.size >= 19)
                assertEquals(results.size, results.map { it.name }.toSet().size)
            }
        }
    }
}
