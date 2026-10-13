package com.example.pitpitch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException

/** Provider response fixtures are captured examples, never production initialization data. */
@RunWith(AndroidJUnit4::class)
class RemoteDataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    @Test fun footballAdapterPreservesRealScoreUtcSourceAndMissingCapabilities() {
        val match = OpenLigaAdapter.matches(fixture("openliga-match.json")).single()
        assertEquals("6 : 0", match.score)
        assertEquals("完场", match.status)
        assertEquals("2025-08-22T18:30:00Z", match.kickoffUtc)
        assertFalse(match.isDemo); assertFalse(match.lineupAvailable); assertFalse(match.statsAvailable)
        assertEquals(6, match.events.size); assertNotNull(match.sourceUpdated)
        assertTrue(match.events.first().label.contains("M. Olise"))
    }
    @Test fun badgeUrlsRemainAttachedToTheCorrectTeamsAndUnsafeLinksAreAbsent() {
        val array = JSONArray(fixture("openliga-match.json")); val raw = array.getJSONObject(0)
        raw.getJSONObject("team1").put("teamIconUrl", "https://example.org/home.svg")
        raw.getJSONObject("team2").put("teamIconUrl", "https://example.org/away.png")
        val match = OpenLigaAdapter.matches(array.toString()).single()
        assertEquals("https://example.org/home.svg", match.homeBadgeUrl)
        assertEquals("https://example.org/away.png", match.awayBadgeUrl)
        raw.getJSONObject("team1").put("teamIconUrl", JSONObject.NULL)
        raw.getJSONObject("team2").put("teamIconUrl", "http://example.org/away.png")
        val missing = OpenLigaAdapter.matches(array.toString()).single()
        assertNull(missing.homeBadgeUrl); assertNull(missing.awayBadgeUrl)
        assertNull(secureBadgeUrl("https://user:password@example.org/logo.png"))
        assertNull(secureBadgeUrl("file:///etc/logo.png"))
    }
    @Test fun futureAndHalfTimeOnlyNeverFabricateScoresOrLiveStatus() {
        val array = JSONArray(fixture("openliga-match.json")); val raw = array.getJSONObject(0)
        raw.put("matchIsFinished", false); raw.put("matchDateTimeUTC", "2099-01-01T00:00:00Z")
        assertEquals("— : —", OpenLigaAdapter.matches(array.toString()).single().score)
        raw.put("matchDateTimeUTC", "2000-01-01T00:00:00Z")
        raw.put("matchResults", JSONArray().put(raw.getJSONArray("matchResults").getJSONObject(0)))
        val old = OpenLigaAdapter.matches(array.toString()).single()
        assertEquals("赛况待更新", old.status); assertEquals("— : —", old.score)
        raw.put("matchDateTimeUTC", JSONObject.NULL)
        assertEquals("时间待定", OpenLigaAdapter.matches(array.toString()).single().status)
    }
    @Test fun f1ResultsAndStandingsUseProviderPositionsStatusAndPoints() {
        val standings = JolpicaAdapter.standings(fixture("drivers.json"), false, 2025)
        assertEquals("24", standings.round); assertEquals("Lando Norris", standings.entries.first().name)
        assertEquals("423", standings.entries.first().points)
        val results = JolpicaAdapter.results(fixture("results.json"))
        assertEquals(20, results.size); assertTrue(results.any { !it.detail.startsWith("Finished") })
        assertTrue(JolpicaAdapter.results("""{"MRData":{"RaceTable":{"Races":[]}}}""").isEmpty())
    }
    @Test fun transportFailuresAndInvalidJsonPropagateWithoutAnyCacheLayer() = runBlocking {
        var failure: Exception? = null
        var body = fixture("openliga-match.json")
        val client = JsonClient(transport = { failure?.let { throw it }; body })
        val url = "https://fixture.invalid/bl1/2025"
        val first = client.load(url, OpenLigaAdapter::matches)
        assertEquals(1, first.data.size)
        // Persistence now lives in the database layer; the HTTP client must surface every failure.
        for (error in listOf(SocketTimeoutException(), SportsHttpException(429), SportsHttpException(500))) {
            failure = error
            try { client.load(url, OpenLigaAdapter::matches); fail("Expected transport failure") }
            catch (thrown: Exception) { assertEquals(readableError(error), readableError(thrown)) }
        }
        failure = null; body = "broken"
        try { client.load(url, OpenLigaAdapter::matches); fail("Expected parse failure") } catch (_: Exception) {}
    }
    @Test fun publicProvidersRespondOnAndroidDevice() = runBlocking {
        val client = JsonClient()
        val football = RemoteFootballRepository(client).season(2025)
        assertTrue(football.data.size >= 9); assertTrue(football.data.all { !it.isDemo })
        val results = client.load("https://api.jolpi.ca/ergast/f1/2025/1/results.json?limit=100", JolpicaAdapter::results)
        assertEquals(20, results.data.size)
        val teams = client.load("https://api.jolpi.ca/ergast/f1/2025/constructorstandings.json?limit=100") { JolpicaAdapter.standings(it, true, 2025) }
        assertTrue(teams.data.entries.isNotEmpty())
    }
}
