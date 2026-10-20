package com.example.pitpitch

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import com.example.pitpitch.data.repository.localBackend
import com.example.pitpitch.feature.*
import com.example.pitpitch.ui.components.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DisplayBadgeIdentityTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingUrlsLoadVerifiedAssetsAcrossEveryBadgeConsumer() = assertConsumers(null)
    @Test fun newUrlsLoadVerifiedAssetsAcrossEveryBadgeConsumer() = assertConsumers("https://example.invalid/new-provider-crest.png")

    private fun assertConsumers(url: String?) {
        runBlocking { localBackend(context).prepare() }
        val archive = SportsArchive(context)
        val season = runBlocking { EuropeanFootballRepository(JsonClient(archive = archive), archive).season(2026) }
        val original = season.matches.first { it.homeTeamId == 1000360 && it.status.contains("完场") }
        val catalog = JSONObject(context.assets.open("team-badges/catalog.json").bufferedReader().use { it.readText() })
        fun sourceAsset(source: String?) = "file:///android_asset/team-badges/" + catalog.getJSONObject(source!!).getString("file")
        val homeAsset = sourceAsset(original.homeBadgeUrl)
        val awayAsset = sourceAsset(original.awayBadgeUrl)
        assertTrue(homeAsset, homeAsset.startsWith("file:///android_asset/team-badges/"))
        val match = original.copy(homeBadgeUrl = url, awayBadgeUrl = url)
        val team = FootballTeam(match.homeTeamId!!, match.home, url)
        val standing = season.tables.getValue("eng.1").data.flatMap { it.entries }.single { it.team.id == team.id }.copy(team = team)
        val state = SportsViewModel.State(footballSeason = 2026, remoteMatches = listOf(match), homeTeam = team)
        val stage = mutableStateOf(-1)
        val successfulModels = CopyOnWriteArrayList<String>()
        val networkRequests = AtomicInteger()
        val previousLoader = context.imageLoader
        val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null)
            .okHttpClient(OkHttpClient.Builder().addInterceptor {
                networkRequests.incrementAndGet()
                throw IOException("Regression test denies network")
            }.build())
            .eventListener(object : EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    assertTrue(result.drawable.intrinsicWidth > 0)
                    successfulModels += request.data.toString()
                }
            }).build()
        Coil.setImageLoader(loader)
        try {
            rule.setContent { MaterialTheme { key(stage.value) {
                when (stage.value) {
                    0 -> FootballFixtureRow(match, ZoneId.of("UTC")) {}
                    1 -> MatchCard(match, ZoneId.of("UTC"))
                    2 -> MatchStatistics(match, listOf(MatchStatistic("射门", "12", "7")))
                    3 -> MatchTimelineHeader(match)
                    4 -> TeamProfileHeader(team, 2026, "eng.1", standing, null)
                    5 -> TeamSelectionScreen(state, {}, {})
                    6 -> SettingsScreen(state, { _, _ -> }, {}, {})
                    7 -> BottomBar(listOf(NavItem("首页", Icons.Default.Star)), 0, {}, team)
                    8 -> StandingRow(standing)
                }
            } } }
            val expected = listOf(listOf(homeAsset, awayAsset), listOf(homeAsset, awayAsset),
                listOf(homeAsset, awayAsset), listOf(homeAsset, awayAsset), listOf(homeAsset, homeAsset),
                listOf(homeAsset, awayAsset), listOf(homeAsset), listOf(homeAsset), listOf(homeAsset))
            for (index in expected.indices) {
                rule.runOnIdle { stage.value = -1 }
                rule.waitForIdle()
                successfulModels.clear()
                rule.runOnIdle { stage.value = index }
                rule.waitUntil(10000) { successfulModels.size >= expected[index].size }
                rule.waitForIdle()
                assertEquals("Badge consumer $index must decode the exact source assets", expected[index].sorted(), successfulModels.sorted())
                assertEquals("Known identity must not fetch the changed/missing URL", 0, networkRequests.get())
            }
        } finally {
            rule.runOnIdle { stage.value = -1 }
            rule.waitForIdle()
            Coil.setImageLoader(previousLoader)
            loader.shutdown()
        }
    }

    @Test fun unknownIdsDoNotBorrowCrestsEvenWhenNamesMatchVerifiedClubs() {
        runBlocking { localBackend(context).prepare() }
        val successes = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val previousLoader = context.imageLoader
        val shown = mutableStateOf(true)
        val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null)
            .okHttpClient(OkHttpClient.Builder().addInterceptor { throw IOException("Regression test denies network") }.build())
            .eventListener(object : EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) { successes += request.data.toString() }
                override fun onError(request: ImageRequest, result: ErrorResult) { failures += request.data.toString() }
            }).build()
        Coil.setImageLoader(loader)
        try {
            rule.setContent { if (shown.value) Column {
                TeamBadge("Manchester United", Int.MAX_VALUE, null)
                TeamBadge("Arsenal", Int.MAX_VALUE - 1, "https://example.invalid/unknown-crest.png")
            } }
            rule.waitUntil(10000) { "https://example.invalid/unknown-crest.png" in failures }
            rule.onNodeWithContentDescription("Manchester United 队徽占位图").assertIsDisplayed()
            rule.onNodeWithContentDescription("Arsenal 队徽占位图").assertIsDisplayed()
            assertTrue(successes.isEmpty())
        } finally {
            rule.runOnIdle { shown.value = false }
            rule.waitForIdle()
            Coil.setImageLoader(previousLoader)
            loader.shutdown()
        }
    }
}
