package com.example.pitpitch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException

/** Provider response fixtures are captured examples, never production initialization data. */
@RunWith(AndroidJUnit4::class)
class RemoteDataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun count(body: String) = JSONArray(body).length()
    @Test fun unsafeOrCredentialedBadgeLinksAreRejected() {
        assertNull(secureBadgeUrl("https://user:password@example.org/logo.png"))
        assertNull(secureBadgeUrl("file:///etc/logo.png"))
        assertNull(secureBadgeUrl("http://example.org/away.png"))
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/359.png", secureBadgeUrl("https://a.espncdn.com/i/teamlogos/soccer/500/359.png"))
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
        assertEquals(1, client.load(url, ::count).data)
        // Persistence now lives in the database layer; the HTTP client must surface every failure.
        for (error in listOf(SocketTimeoutException(), SportsHttpException(429), SportsHttpException(500))) {
            failure = error
            try { client.load(url, ::count); fail("Expected transport failure") }
            catch (thrown: Exception) { assertEquals(readableError(error), readableError(thrown)) }
        }
        failure = null; body = "broken"
        try { client.load(url, ::count); fail("Expected parse failure") } catch (_: Exception) {}
    }
    @Test fun publicProvidersRespondOnAndroidDevice() = runBlocking {
        val client = JsonClient()
        val results = client.load("https://api.jolpi.ca/ergast/f1/2025/1/results.json?limit=100", JolpicaAdapter::results)
        assertEquals(20, results.data.size)
        val teams = client.load("https://api.jolpi.ca/ergast/f1/2025/constructorstandings.json?limit=100") { JolpicaAdapter.standings(it, true, 2025) }
        assertTrue(teams.data.entries.isNotEmpty())
    }
}
