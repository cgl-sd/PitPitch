package com.example.pitpitch

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.RaceRepository
import com.example.pitpitch.data.repository.LocalBackendRepository
import com.example.pitpitch.feature.FootballScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FootballStandingsRefreshTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val year get() = SportsViewModel.State().footballSeason
    private val teamId = 1_000_382

    @Test fun enteringStandingsKeepsCachedRowsThenDisplaysAndPersistsProviderUpdate() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        exerciseUpdate(JsonClient(transport = { url ->
            if (url == EspnFootballAdapter.standingsUrl("eng.1", year)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                standingsBody(year, 6, 18)
            } else "{\"events\":[]}"
        }), refreshOnEntry = true, beforeUpdate = {
            rule.waitUntil(5000) { entered.count == 0L }
            assertPlayed("5")
            release.countDown()
        }, release = { release.countDown() })
    }

    @Test fun dayRefreshPublishesUpdatedStandingsAlongsideScheduleState() {
        exerciseUpdate(JsonClient(transport = { url ->
            if (url == EspnFootballAdapter.standingsUrl("eng.1", year)) standingsBody(year, 6, 18)
            else "{\"events\":[]}"
        }), refreshOnEntry = false, beforeUpdate = { model ->
            assertPlayed("5")
            rule.runOnUiThread { model.refreshDayOverlay(true) }
        })
    }

    private fun exerciseUpdate(client: JsonClient, refreshOnEntry: Boolean,
        beforeUpdate: (SportsViewModel) -> Unit, release: () -> Unit = {}) {
        val db = Room.inMemoryDatabaseBuilder(app, PitPitchDatabase::class.java).build()
        val store = FootballStore(db)
        val owner = ViewModelStore()
        lateinit var model: SportsViewModel
        try {
            runBlocking {
                // Represent an already installed database, with its independently cached standings.
                val revision = app.assets.open("local-backend-revision.txt").bufferedReader().use { it.readText().trim() }
                db.meta().put(MetaRow("localDataRevision", revision))
                db.meta().put(MetaRow("seedVersion", DatabaseSeeder.SEED_VERSION))
                db.meta().put(MetaRow("seedDataRevision", revision))
                db.meta().put(MetaRow("completeF1ResultsVersion", "2"))
                db.meta().put(MetaRow("f1MediaBindingVersion", "1"))
                db.meta().put(MetaRow("legacyHttpImported", "1"))
                db.meta().put(MetaRow("legacyCalendarImported", "1"))
                store.writeStandings("eng.1", year, EspnFootballAdapter.tables(standingsBody(year, 5, 15), year), "2020-01-01T00:00:00Z")
            }
            rule.runOnUiThread {
                model = SportsViewModel(app, client, object : RaceRepository {
                    override suspend fun season(year: Int, force: Boolean) = emptyList<F1Race>()
                }, db = db, backend = LocalBackendRepository(app, db))
                owner.put("standings-update", model)
            }
            rule.waitUntil(20000) { model.state.value.ready && !model.state.value.footballLoading &&
                model.state.value.footballTables["eng.1"]?.data?.flatMap { it.entries }?.any { it.team.id == teamId } == true }
            rule.setContent {
                val state = model.state.collectAsState().value
                MaterialTheme { FootballScreen(state, ZoneId.systemDefault(), {},
                    refreshStandings = { if (refreshOnEntry) model.refreshFootballStandings() }) }
            }
            rule.onNodeWithTag("football-section-1").performClick()
            beforeUpdate(model)
            rule.waitUntil(10000) { model.state.value.footballTables["eng.1"]?.data?.flatMap { it.entries }
                ?.firstOrNull { it.team.id == teamId }?.played == "6" }
            assertPlayed("6")
            val inRow = hasAnyAncestor(hasTestTag("standing-$teamId"))
            rule.onNode(hasText("18") and inRow, useUnmergedTree = true).assertIsDisplayed()
            val persisted = runBlocking { store.competitionTables("eng.1", year) }!!.first.flatMap { it.entries }.single()
            assertEquals("6", persisted.played)
            assertEquals("18", persisted.points)
            assertEquals("1", persisted.position)
        } finally {
            release()
            rule.runOnUiThread { owner.clear() }
            db.close()
        }
    }

    private fun assertPlayed(value: String) {
        rule.onNode(hasText(value) and hasAnyAncestor(hasTestTag("standing-$teamId")), useUnmergedTree = true).assertIsDisplayed()
    }

    private fun standingsBody(season: Int, played: Int, points: Int): String {
        val stats = JSONArray()
        mapOf("rank" to 1, "gamesPlayed" to played, "wins" to played, "ties" to 0, "losses" to 0,
            "pointsFor" to 12, "pointsAgainst" to 2, "pointDifferential" to 10, "points" to points).forEach { (name, value) ->
            stats.put(JSONObject().put("name", name).put("value", value).put("displayValue", value.toString()))
        }
        val entry = JSONObject().put("team", JSONObject().put("id", "382").put("displayName", "Manchester City")).put("stats", stats)
        return JSONObject().put("season", JSONObject().put("year", season)).put("name", "Overall")
            .put("standings", JSONObject().put("entries", JSONArray().put(entry))).toString()
    }
}
