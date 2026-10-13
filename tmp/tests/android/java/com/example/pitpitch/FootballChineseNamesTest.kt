package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.MatchCard
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

class FootballChineseNamesTest {
    @org.junit.Before fun prepareBackend() = runBlocking { com.example.pitpitch.data.repository.localBackend(context).prepare() }
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun packagedNamesKeepUnknownNamesAndOriginalArchiveUnchanged() {
        val names = FootballNames.get(context)
        assertEquals("曼联", names.team(1000360, "Manchester United"))
        assertEquals("老特拉福德球场", names.venue("Old Trafford"))
        assertEquals("New club", names.team(1999999, "New club"))
        assertEquals("Manchester United", names.team(1999999, "Manchester United"))
        assertEquals("New ground", names.venue("New ground"))
        assertEquals("Arsenal", names.team(null, "Arsenal"))
        val matches = runBlocking { val source = SportsArchive(context); EuropeanFootballRepository(JsonClient(archive = source), source).season(2025).matches }
        val match = matches.first { it.homeTeamId == 1000360 && it.stadium == "Old Trafford" }
        assertEquals("Manchester United", match.home)
        assertNotNull(match.awayTeamId)
    }
    @Test fun verifiedClubNamesDropClubSuffixAndScheduleNamesStayOnOneLine() {
        val names = FootballNames.get(context)
        assertEquals("加拉塔萨雷", names.team(1000432, "Galatasaray"))
        assertEquals("马德里竞技", names.team(1001068, "Atlético Madrid"))
        assertEquals("罗马", names.team(1000104, "AS Roma"))
        val catalog = org.json.JSONObject(context.assets.open("football-chinese-names.json").bufferedReader().use { it.readText() })
        val teams = catalog.getJSONObject("teams")
        teams.keys().forEach { id -> assertFalse(teams.getJSONObject(id).getString("name").endsWith("俱乐部")) }
        val match = runBlocking { val source = SportsArchive(context); EuropeanFootballRepository(JsonClient(archive = source), source).season(2026).matches }
            .first { it.homeTeamId == 1000432 && it.awayTeamId == 1000083 }
        val scale = androidx.compose.runtime.mutableStateOf(1f)
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, scale.value)) {
                androidx.compose.material3.MaterialTheme(colorScheme = com.example.pitpitch.ui.SportsColors) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(320.dp)) {
                        com.example.pitpitch.feature.FootballFixtureRow(match, ZoneId.systemDefault()) {}
                    }
                }
            }
        }
        for (font in listOf(1f, 1.6f)) {
            rule.runOnUiThread { scale.value = font }
            rule.waitForIdle()
            for (side in listOf("home", "away")) {
                val node = rule.onNodeWithTag("fixture-$side-name-${match.id}", useUnmergedTree = true)
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.single().lineCount)
                assertFalse("Verified name must be visible in full", layouts.single().hasVisualOverflow)
                assertFalse(layouts.single().isLineEllipsized(0))
                val id = if (side == "home") match.homeTeamId else match.awayTeamId
                val original = if (side == "home") match.homeShort else match.awayShort
                node.assertTextEquals(names.team(id, original))
            }
            val file = File(context.cacheDir, "pitpitch-validation/single-line-chinese-$font.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals("Galatasaray", match.home)
    }
    @Test fun all241ReviewedNamesRenderInFullOnNarrowScheduleRows() {
        val catalog = org.json.JSONObject(context.assets.open("football-chinese-names.json").bufferedReader().use { it.readText() })
        val teams = catalog.getJSONObject("teams")
        assertEquals(241, teams.length())
        val names = FootballNames.get(context)
        val base = runBlocking { val source = SportsArchive(context); EuropeanFootballRepository(JsonClient(archive = source), source).season(2026).matches }.first()
        val row = androidx.compose.runtime.mutableStateOf(base)
        val scale = androidx.compose.runtime.mutableStateOf(1f)
        // Controlled presentation fixtures exercise each verified identity; production source records stay untouched.
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, scale.value)) {
                androidx.compose.material3.MaterialTheme(colorScheme = com.example.pitpitch.ui.SportsColors) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(320.dp)) {
                        com.example.pitpitch.feature.FootballFixtureRow(row.value, ZoneId.systemDefault()) {}
                    }
                }
            }
        }
        for (font in listOf(1f, 1.6f)) for (id in teams.keys().asSequence().toList()) {
            val original = teams.getJSONObject(id).getJSONArray("originalNames").getString(0)
            rule.runOnUiThread { scale.value = font; row.value = base.copy(homeTeamId = id.toInt(), awayTeamId = id.toInt(), home = original, homeShort = original, away = original, awayShort = original, homeBadgeUrl = null, awayBadgeUrl = null) }
            rule.waitForIdle()
            for (side in listOf("home", "away")) {
                val node = rule.onNodeWithTag("fixture-$side-name-${base.id}", useUnmergedTree = true)
                node.assertTextEquals(names.team(id.toInt(), original))
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("$id font=$font", 1, layouts.single().lineCount)
                assertFalse("$id font=$font overflow", layouts.single().hasVisualOverflow)
                assertFalse("$id font=$font ellipsis", layouts.single().isLineEllipsized(0))
            }
        }
    }
    @Test fun realMatchDisplaysChineseTeamsAndVenueWithoutChangingScoreOrTime() {
        val match = runBlocking { val source = SportsArchive(context); EuropeanFootballRepository(JsonClient(archive = source), source).season(2025).matches }.first { it.homeTeamId == 1000360 && it.stadium == "Old Trafford" }
        rule.setContent { MatchCard(match, ZoneId.systemDefault()) }
        rule.onNodeWithText("曼联").assertExists()
        rule.onNodeWithText("老特拉福德球场", substring = true).assertExists()
        rule.onNodeWithText(match.score).assertExists()
        rule.onNodeWithText(com.example.pitpitch.feature.displayInstant(match.kickoffUtc, ZoneId.systemDefault())).assertExists()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File(context.cacheDir, "pitpitch-validation/chinese-match.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
