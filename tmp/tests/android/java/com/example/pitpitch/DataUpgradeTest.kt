package com.example.pitpitch

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.example.pitpitch.data.db.PitPitchDatabase
import com.example.pitpitch.data.db.FootballStore
import com.example.pitpitch.data.db.MetaRow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Instant

class DataUpgradeTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = "https://api.jolpi.ca/ergast/f1/2026.json?limit=100"
    @Test fun normalAndFailedRefreshSelectSameVersionAfterUpgrade() = runBlocking {
        val archive = SportsArchive(context)
        val (body, time) = archive.response(url)!!
        val prefs = context.getSharedPreferences("test_data_upgrade", 0)
        val client = JsonClient(prefs, transport = { throw SocketTimeoutException() }, archive = archive)
        try {
            // Actual archived payload; only its test-cache acquisition time changes.
            for ((cacheTime, fromPackage) in listOf(
                Instant.parse(time).minusSeconds(60).toString() to true,
                time to true,
                Instant.parse(time).plusSeconds(60).toString() to false,
                "invalid" to true,
            )) {
                prefs.edit().putString("response:$url", body).putString("time:$url", cacheTime).commit()
                val initial = client.archived(url) { F1Api.parseSeason(it, 2026) }!!
                val failed = client.load(url) { F1Api.parseSeason(it, 2026) }
                assertEquals(fromPackage, initial.archived)
                assertEquals(initial.data, failed.data)
                assertEquals(initial.fetchedUtc, failed.fetchedUtc)
                assertEquals(initial.cached, failed.cached)
                assertEquals(initial.archived, failed.archived)
                assertNotNull(failed.error)
                assertEquals(cacheTime, prefs.getString("time:$url", null))
            }
            prefs.edit().putString("response:$url", "broken JSON").putString("time:$url", Instant.parse(time).plusSeconds(60).toString()).commit()
            assertTrue(client.load(url) { F1Api.parseSeason(it, 2026) }.archived)
            try { client.load("https://fixture.invalid/other-season") { F1Api.parseSeason(it, 2026) }; fail("Wrong resource fallback") }
            catch (_: SocketTimeoutException) { }
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun f1StartupAndOfflineRefreshReplaceOldCalendarWithoutRestampingIt() {
        val prefs = context.getSharedPreferences("test_calendar_upgrade", 0)
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        val cache = RaceCache(prefs)
        val archive = SportsArchive(context, db)
        val bundled = SportsArchive(context).read(url) { F1Api.parseSeason(it, 2026) }!!
        val oldTime = Instant.parse(bundled.fetchedUtc).minusSeconds(60).toString()
        // Previously cached successful empty response is older than this APK calendar.
        cache.saveSeason(2026, emptyList(), oldTime)
        val owner = ViewModelStore()
        lateinit var model: SportsViewModel
        try {
            runBlocking {
                // This fixture owns its Room and legacy cache; never import installed user caches.
                db.meta().put(MetaRow("legacyHttpImported", "1"))
                db.meta().put(MetaRow("legacyCalendarImported", "1"))
            }
            rule.runOnUiThread {
                model = SportsViewModel(context.applicationContext as Application,
                    JsonClient(archive = archive, database = db),
                    object : RaceRepository { override suspend fun season(year: Int, force: Boolean): List<F1Race> = throw SocketTimeoutException("offline calendar") },
                    cache, db = db, savedStateHandle = SavedStateHandle(mapOf("f1Year" to 2026)), backend = LocalBackendRepository(context, db))
                owner.put("upgrade", model)
            }
            rule.waitUntil(60000) {
                val state = model.state.value
                state.season == 2026 && state.calendars[2026]?.isNotEmpty() == true &&
                    state.updated != null && !state.loading
            }
            assertEquals(bundled.data, model.state.value.calendars[2026])
            assertEquals(bundled.fetchedUtc, model.updated(2026))
            rule.runOnUiThread { model.refresh() }
            rule.waitUntil(10000) { !model.state.value.loading && model.state.value.error != null }
            assertEquals(bundled.data, model.state.value.calendars[2026])
            assertEquals(oldTime, cache.updated(2026))
            assertEquals(bundled.fetchedUtc, model.state.value.updated)
            assertEquals(readableError(SocketTimeoutException("offline calendar")), model.state.value.error)
            val stored = runBlocking { FootballStore(db).f1Races(2026) }
            assertEquals(bundled.data, stored.first)
            assertEquals(bundled.fetchedUtc, stored.second)
            // A valid newer empty calendar must stay empty, but corrupt JSON must not.
            cache.saveSeason(2026, emptyList(), Instant.parse(bundled.fetchedUtc).plusSeconds(60).toString())
            assertTrue(preferredLocalSnapshot(bundled, cache.snapshot(2026))!!.data.isEmpty())
            prefs.edit().putString("f1_calendar_2026", "broken JSON").commit()
            assertNull(cache.snapshot(2026))
            assertEquals(bundled, preferredLocalSnapshot(bundled, cache.snapshot(2026)))
        } finally { rule.runOnUiThread { owner.clear() }; db.close(); prefs.edit().clear().commit() }
    }
}
