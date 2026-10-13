package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import com.example.pitpitch.feature.FootballScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

class FootballPointsTableTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val archive get() = SportsArchive(context)
    @Test fun allLeaguesLoadPackagedBadgesWithoutUrls() {
        runBlocking { com.example.pitpitch.data.repository.localBackend(context).prepare() }
        val source = archive
        val season = runBlocking { EuropeanFootballRepository(JsonClient(archive = source), source).season(2026) }
        val legacyTables = season.tables.mapValues { (_, snapshot) -> snapshot.copy(data = snapshot.data.map { table ->
            table.copy(entries = table.entries.map { it.copy(team = it.team.copy(badgeUrl = null)) })
        }) }
        val s = mutableStateOf(SportsViewModel.State(footballSeason = 2026, matches = season.matches, footballTables = legacyTables))
        rule.setContent { FootballScreen(s.value, ZoneId.systemDefault(), {}) }
        rule.onNodeWithTag("football-section-1").performClick()
        var checked = 0
        for (league in footballCompetitions) {
            rule.runOnIdle { s.value = s.value.copy(footballCompetition = league.id) }
            rule.waitForIdle()
            val rows = legacyTables.getValue(league.id).data.flatMap { it.entries }
            for (row in rows) {
                assertNotNull("${league.id}:${row.team.id}", BundledTeamBadges.asset(context, null, row.team.id))
                checked++
            }
            val row = rows.first()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("standing-${row.team.id}"))
            val label = FootballNames.get(context).team(row.team.id, row.team.name) + " 队徽"
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            capture("table-badges-${league.id}")
        }
        assertEquals(168, checked)
    }
    @Test fun realHistoricalTablesHaveZonesConfirmedIconsAndSeparatedTabs() {
        val source = archive
        val repo = EuropeanFootballRepository(JsonClient(archive = source), source)
        val season = runBlocking { repo.season(2025) }
        val s = mutableStateOf(SportsViewModel.State(footballSeason = 2025, matches = season.matches, footballTables = season.tables))
        rule.setContent { FootballScreen(s.value, ZoneId.systemDefault(), {}, competition = { s.value = s.value.copy(footballCompetition = it) }) }
        rule.onNodeWithTag("football-section-3").assertDoesNotExist()
        val leagues = rule.onNodeWithTag("football-leagues").fetchSemanticsNode().boundsInRoot
        val tabs = rule.onNodeWithTag("football-tabs").fetchSemanticsNode().boundsInRoot
        assertTrue("League and section hit areas must be separated", tabs.top > leagues.bottom)
        rule.onNodeWithTag("football-section-1").performClick()
        rule.onNodeWithText("胜/平/负").assertExists()
        assertTrue(rule.onAllNodesWithContentDescription("已确认欧冠席位").fetchSemanticsNodes().isNotEmpty())
        capture("football-table-epl")
        val table = season.tables.getValue("eng.1").data.single()
        rule.onNodeWithText("净").assertDoesNotExist()
        val header = hasAnyAncestor(hasTestTag("football-points-table-${table.name}"))
        val points = rule.onNode(hasText("积分") and header).fetchSemanticsNode().boundsInRoot
        val goals = rule.onNode(hasText("进/失") and header).fetchSemanticsNode().boundsInRoot
        assertTrue(points.left >= goals.right)
        assertEquals(5, table.entries.count { standingMark(it, table, "eng.1", 2025).zone == TableZone.CHAMPIONS })
        assertEquals(3, table.entries.count { standingMark(it, table, "eng.1", 2025).zone == TableZone.RELEGATION && standingMark(it, table, "eng.1", 2025).confirmed })
        for (league in listOf("uefa.champions", "uefa.europa")) {
            rule.runOnUiThread { s.value = s.value.copy(footballCompetition = league) }
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithContentDescription("已确认晋级").fetchSemanticsNodes().isNotEmpty())
            capture("football-table-${league.substringAfter('.')}")
        }
    }
    @Test fun rulesFollowTheLastTableAndTrackCompetitionAndSeasonFormat() {
        runBlocking { com.example.pitpitch.data.repository.localBackend(context).prepare() }
        val source = archive
        val repo = EuropeanFootballRepository(JsonClient(archive = source), source)
        val seasons = runBlocking { listOf(2023, 2024, 2025).associateWith { repo.season(it) } }
        val initial = seasons.getValue(2025)
        val s = mutableStateOf(SportsViewModel.State(footballSeason = 2025, matches = initial.matches, footballTables = initial.tables))
        rule.setContent { FootballScreen(s.value, ZoneId.systemDefault(), {},
            competition = { s.value = s.value.copy(footballCompetition = it) },
            season = { year ->
                val selected = seasons.getValue(year)
                s.value = s.value.copy(footballSeason = year, matches = selected.matches, footballTables = selected.tables)
            }) }
        rule.onNodeWithTag("football-section-1").performClick()
        for (league in footballCompetitions) {
            rule.onNodeWithText(league.title, useUnmergedTree = true).performScrollTo().performClick()
            assertRulesAfterLastStanding(initial.tables.getValue(league.id).data.last(), league.title, 2025)
        }
        for (league in footballCompetitions.filter { it.european }) {
            rule.onNodeWithText(league.title, useUnmergedTree = true).performScrollTo().performClick()
            for (year in listOf(2023, 2024)) {
                rule.onNodeWithTag("football-season").performClick()
                rule.onNodeWithTag("football-season-option-$year").performClick()
                val tables = seasons.getValue(year).tables.getValue(league.id).data
                if (year == 2023) assertTrue("Historical group tables must all be present", tables.size > 1)
                assertRulesAfterLastStanding(tables.last(), league.title, year)
                val inRules = hasAnyAncestor(hasTestTag("football-table-rules"))
                val format = if (year < 2024) "小组" else "36"
                assertTrue("Rules must describe the selected season's competition format",
                    rule.onAllNodes(hasText(format, substring = true) and inRules).fetchSemanticsNodes().isNotEmpty())
            }
            // The phase selector switches fixtures only: standings stay on the league phase.
            rule.onNodeWithText("淘汰赛").performClick()
            rule.onNodeWithTag("football-table-rules").assertExists()
        }
    }
    private fun assertRulesAfterLastStanding(table: FootballTable, title: String, year: Int) {
        val list = rule.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasTestTag("football-table-rules"))
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithTag("football-table-rules").fetchSemanticsNode().boundsInRoot
        if (footer.top < viewport.top + 80f) list.performTouchInput {
            swipeDown(startY = height * 0.25f, endY = height * 0.65f)
        }
        rule.onAllNodesWithTag("football-table-rules").assertCountEquals(1)
        val lastStanding = rule.onNodeWithTag("standing-${table.entries.last().team.id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rules = rule.onNodeWithTag("football-table-rules").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Rules must follow the last standing of the final table", lastStanding.bottom <= rules.top)
        val inRules = hasAnyAncestor(hasTestTag("football-table-rules"))
        rule.onNode(hasText(title, substring = true) and hasText("$year/${year + 1}", substring = true) and inRules).assertIsDisplayed()
    }
    @Test fun supplementalNotesNeverAttachToChangedCachedPoints() {
        val a = archive
        val url = "${EspnFootballAdapter.WEB}eng.1/standings?season=2025"
        val original = a.read(url) { EspnFootballAdapter.tables(it, 2025) }!!.data
        assertTrue(a.tableNotes(url, 2025, original).single().entries.first().providerNote != null)
        val altered = original.map { it.copy(entries = it.entries.map { row -> row.copy(points = "999") }) }
        assertTrue(a.tableNotes(url, 2025, altered).flatMap { it.entries }.all { it.providerNote == null })
    }
    private fun capture(name: String) {
        val f = File(context.cacheDir, "pitpitch-validation/$name.png"); f.parentFile!!.mkdirs()
        f.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
