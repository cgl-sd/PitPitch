package com.example.pitpitch

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.example.pitpitch.app.SportsViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FootballScrollPositionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun homepageMatchTransitionKeepsOutgoingViewportAndRowPosition() {
        val fixture = SemanticsMatcher("home fixture") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("match-") == true }
        rule.waitUntil(20000) { rule.onAllNodes(fixturesOnHome(fixture)).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onAllNodes(fixturesOnHome(fixture)).fetchSemanticsNodes().first()
        val tag = node.config[SemanticsProperties.TestTag]
        val row = node.boundsInRoot
        val viewport = rule.onNodeWithTag("main-page-viewport").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("main-page-viewport"))).performTouchInput {
                click(androidx.compose.ui.geometry.Offset(width * .1f, height * .2f))
            }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val during = rule.onNodeWithTag("main-page-viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val duringRow = rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("main-page-viewport")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.height, during.height, 1f)
            assertEquals(row.top, duringRow.top, 1f)
        } finally { rule.mainClock.autoAdvance = true }
    }
    private fun fixturesOnHome(fixture: SemanticsMatcher) = fixture and hasAnyAncestor(hasTestTag("main-page-viewport"))
    @Test fun manuallyScrolledF1RaceStaysInPlaceAfterReturningFromDetails() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-2").performClick()
        rule.waitUntil(20000) { ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.races.isNotEmpty() }
        val race = ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.races.minBy { it.round }
        val tag = "race-${race.id}"
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        val top = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag(tag).performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .2f, height * .2f)) }
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("返回上一级").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("返回上一级").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("f1-section-0").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals(top, rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
    }
    @Test fun transitionDoesNotResizeCoventryScheduleOrMoveClickedRow() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-1").performClick()
        val model = ViewModelProvider(rule.activity)[SportsViewModel::class.java]
        rule.activityRule.scenario.onActivity { model.footballSeason(2026); model.footballCompetition("eng.1") }
        rule.waitUntil(20000) { model.state.value.footballSeason == 2026 && !model.state.value.footballLoading && model.state.value.matches.isNotEmpty() }
        val coventry = model.state.value.matches.first { it.competitionId == "eng.1" && it.roundNumber == 6 && it.home.contains("Coventry") }
        val tag = "match-${coventry.id}"
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        val page = rule.onNodeWithTag("football-page").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag(tag).performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .1f, height * .2f)) }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val duringPage = rule.onNodeWithTag("football-page", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val duringRow = rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("football-page")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Outgoing schedule viewport must stay unchanged", page.height, duringPage.height, 1f)
            assertEquals("Clicked fixture must not move during the transition", row.top, duringRow.top, 1f)
        } finally { rule.mainClock.autoAdvance = true }
    }
    @Test fun openingDetailAndReturningKeepManuallyScrolledFixturePosition() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-1").performClick()
        rule.activityRule.scenario.onActivity { ViewModelProvider(it)[SportsViewModel::class.java].footballSeason(2025) }
        val fixtures = SemanticsMatcher("fixture row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("match-") == true }
        rule.waitUntil(20000) {
            val state = ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value
            state.footballSeason == 2025 && !state.footballLoading && rule.onAllNodes(fixtures).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.waitForIdle()
        val first = rule.onAllNodes(fixtures).fetchSemanticsNodes().first()
        val tag = first.config[SemanticsProperties.TestTag]
        val top = first.boundsInRoot.top
        val round = rule.onNodeWithTag("football-round").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("match-score-status").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("返回上一级").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("football-round").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals(top, rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(round, rule.onNodeWithTag("football-round").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        val file = java.io.File(rule.activity.cacheDir, "pitpitch-validation/football-position-after-detail.png")
        file.parentFile!!.mkdirs()
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
