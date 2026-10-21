package com.example.pitpitch

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.RaceRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FootballSeasonIsolationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun earlyHomeScorePublicationCannotReplaceAHistoricalSelection() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val archive = SportsArchive(app)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = ViewModelStore()
        val prefs = app.getSharedPreferences("test_home_refresh_isolation", 0)
        lateinit var model: SportsViewModel
        try {
            rule.runOnUiThread {
                model = SportsViewModel(app, JsonClient(archive = archive, transport = { url ->
                    if ("/scoreboard?" in url) {
                        entered.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                    throw SocketTimeoutException("controlled offline refresh")
                }), object : RaceRepository {
                    override suspend fun season(year: Int, force: Boolean): List<F1Race> = throw SocketTimeoutException()
                }, RaceCache(prefs))
                owner.put("home-isolation", model)
            }
            rule.waitUntil(40000) { model.state.value.homeReady && !model.state.value.footballLoading }
            rule.runOnUiThread { model.refreshHome(includeF1 = false) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            rule.runOnUiThread { model.footballSeason(2020) }
            rule.waitUntil(10000) { model.state.value.footballSeason == 2020 && !model.state.value.footballLoading }
            val historical = model.state.value.matches
            assertTrue(historical.isNotEmpty())
            release.countDown()
            rule.waitUntil(30000) { !model.state.value.homeRefreshing }
            assertEquals(2020, model.state.value.footballSeason)
            assertEquals(historical, model.state.value.matches)
            assertTrue(model.state.value.homeFootball.all { it.date >= "${currentFootballYear()}-01-01" })
        } finally {
            release.countDown()
            rule.runOnUiThread { owner.clear() }
            prefs.edit().clear().commit()
        }
    }

    @Test fun lateCurrentSeasonOverlayCannotReplaceHistoricalSelection() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val archive = SportsArchive(app)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val owner = ViewModelStore()
        val prefs = app.getSharedPreferences("test_season_isolation", 0)
        lateinit var model: SportsViewModel
        try {
            rule.runOnUiThread {
                model = SportsViewModel(app, JsonClient(archive = archive, transport = {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    returned.set(true)
                    throw SocketTimeoutException("controlled delayed request")
                }), object : RaceRepository {
                    override suspend fun season(year: Int, force: Boolean): List<F1Race> = throw SocketTimeoutException()
                }, RaceCache(prefs))
                owner.put("isolation", model)
            }
            rule.waitUntil(40000) { model.state.value.ready && !model.state.value.footballLoading && model.state.value.matches.isNotEmpty() }
            rule.runOnUiThread { model.refreshDayOverlay(true) }
            assertTrue("refresh entered transport", entered.await(5, TimeUnit.SECONDS))
            rule.runOnUiThread { model.footballSeason(2020) }
            rule.waitUntil(10000) { model.state.value.footballSeason == 2020 && !model.state.value.footballLoading && model.state.value.matches.isNotEmpty() }
            val historical = model.state.value.matches
            assertTrue(historical.all { it.date < "2022-01-01" })
            release.countDown()
            rule.waitUntil(10000) { returned.get() }
            // Exercise preference emissions and another season selection after the blocked current-season request finishes.
            rule.runOnUiThread { model.footballSeason(2021) }
            rule.waitUntil(10000) { !model.state.value.footballLoading && model.state.value.matches.isNotEmpty() }
            assertTrue(model.state.value.matches.all { it.date < "2023-01-01" })
            rule.runOnUiThread { model.footballSeason(2020) }
            rule.waitUntil(10000) { !model.state.value.footballLoading && model.state.value.matches.isNotEmpty() }
            assertEquals(historical, model.state.value.matches)
            assertEquals(2020, model.state.value.footballSeason)
        } finally {
            release.countDown()
            rule.runOnUiThread { owner.clear() }
            prefs.edit().clear().commit()
        }
    }
}
