package com.example.pitpitch

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.local.UserStore
import com.example.pitpitch.data.local.defaultHomeTeam
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TeamProfileNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun matchBadgesOpenBothTeamsAndBackRestoresMatch() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-1").performClick()
        rule.activityRule.scenario.onActivity {
            ViewModelProvider(it)[SportsViewModel::class.java].footballSeason(2025)
        }
        val fixture = SemanticsMatcher("football fixture") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("match-") == true
        }
        rule.waitUntil(20000) {
            val state = ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value
            state.footballSeason == 2025 && !state.footballLoading && rule.onAllNodes(fixture).fetchSemanticsNodes().isNotEmpty()
        }
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val visible = rule.onAllNodes(fixture).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f && it.boundsInRoot.top >= window.top && it.boundsInRoot.bottom <= window.bottom }
            .minBy { it.boundsInRoot.top }
        val tag = visible.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
        val match = ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.matches.first { "match-${it.id}" == tag }
        // The reminder bell sits in the row centre; click the left half so the row itself opens the detail page.
        rule.onNodeWithTag(tag).performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .1f, height * .5f)) }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("match-team-home").fetchSemanticsNodes().isNotEmpty() }
        for ((side, id, originalName) in listOf(Triple("home", match.homeTeamId!!, match.home), Triple("away", match.awayTeamId!!, match.away))) {
            rule.onNodeWithTag("match-team-$side").assertHasClickAction().performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithTag("team-profile").fetchSemanticsNodes().isNotEmpty() }
            val name = com.example.pitpitch.data.FootballNames.get(rule.activity).team(id, originalName)
            rule.onNode(hasText(name) and hasAnyAncestor(hasTestTag("team-profile"))).assertExists()
            rule.waitUntil(20000) {
                ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.squads.containsKey("2025|${match.competitionId}|$id")
            }
            capture("match-team-$side-navigation")
            rule.onNodeWithContentDescription("返回上一级").performClick()
            rule.onNodeWithTag(tag).assertExists()
            rule.onNodeWithTag("match-team-home").assertExists()
        }
        assertEquals(2025, ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.footballSeason)
    }
    @Test fun mainTeamOpensScheduleWithOwnRosterAndSelectionLivesInSettings() {
        val store = UserStore(rule.activity)
        val before = runBlocking { store.settings.first() }
        try {
            runBlocking { store.homeTeam(defaultHomeTeam) }
            rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("主队：曼联，查看球队详情").fetchSemanticsNodes().isNotEmpty() }
            rule.activityRule.scenario.onActivity { ViewModelProvider(it)[SportsViewModel::class.java].footballCompetition("esp.1") }
            rule.onNodeWithTag("nav-team").performClick()
            rule.onNodeWithTag("team-profile").assertExists()
            rule.onNodeWithText("选择你的主队").assertDoesNotExist()
            rule.onNode(hasText("赛程") and hasClickAction()).assertIsSelected()
            rule.waitUntil(20000) { ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.squads.keys.any { it.endsWith("|eng.1|1000360") } }
            val state = ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value
            assertEquals("esp.1", state.footballCompetition)
            assertTrue(state.matches.any { it.homeTeamId == 1000360 || it.awayTeamId == 1000360 })
            capture("team-main-schedule")
            rule.onNode(hasText("队员") and hasClickAction()).performClick()
            for (position in listOf("前锋", "中场", "后卫", "门将")) {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("team-position-$position"))
                rule.onNodeWithTag("team-position-$position").assertExists()
            }
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            capture("team-main-players")
            rule.onNodeWithContentDescription("返回上一级").performClick()
            runBlocking { store.homeTeam(null) }
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("选择主队").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav-team").performClick()
            rule.onNodeWithText("选择你的主队").assertExists()
            rule.onNodeWithContentDescription("返回上一级").performClick()
            rule.onNodeWithTag("open-settings").performClick()
            rule.onNodeWithTag("settings-select-team").performClick()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("team-1000359"))
            rule.onNodeWithTag("team-1000359").performClick()
            rule.onNodeWithTag("settings-select-team").assertExists()
            rule.waitUntil(10000) { runBlocking { store.settings.first().homeTeam?.id == 1000359 } }
            rule.onNodeWithContentDescription("返回上一级").performClick()
            rule.onNodeWithTag("nav-team").performClick()
            rule.onNodeWithTag("team-profile").assertExists()
            rule.onNode(hasText("赛程") and hasClickAction()).assertIsSelected()
        } finally { runBlocking { store.homeTeam(before.homeTeam) } }
    }
    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(rule.activity.cacheDir, "pitpitch-validation/$name.png"); file.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun teamDirectoryOpensSeparateProfileAndBackRestoresDirectory() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-1").performClick()
        rule.onNodeWithTag("football-section-1").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("standing-1000360"))
        rule.onNodeWithTag("standing-1000360").performScrollTo().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("team-profile").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("team-profile").assertExists()
        rule.onNodeWithTag("nav-1").assertDoesNotExist()
        rule.onNodeWithTag("football-section-3").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回上一级").performClick()
        rule.onNodeWithTag("football-section-1").assertExists()
        rule.onNodeWithTag("team-profile").assertDoesNotExist()
    }
}
