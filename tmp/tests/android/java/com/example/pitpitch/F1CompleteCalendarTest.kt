package com.example.pitpitch

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.F1QualifyingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

class F1CompleteCalendarTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun calendarContainsOneOrderedListAndDetailsShowFullQualifyingAndRaceResults() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-2").performClick()
        rule.onNodeWithTag("f1-section-0").performClick()
        rule.chooseScheduleSeason("f1-season", 2026)
        rule.onNodeWithTag("f1-weekend").assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithTag("race-f1-2026-1").assertIsDisplayed()
        val archive = SportsArchive(rule.activity)
        val calendar = archive.read("https://api.jolpi.ca/ergast/f1/2026.json?limit=100") { F1Api.parseSeason(it, 2026) }!!.data
        val upcoming = runBlocking { FootballStore(PitPitchDatabase.build(rule.activity)).f1Races(2026).first }.sortedBy { it.date }.firstOrNull { it.isUpcoming() }
        if (upcoming != null) {
            rule.scrollToScheduleTag("race-${upcoming.id}")
            rule.onNodeWithTag("f1-calendar-expanded-${upcoming.id}", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("练习赛", useUnmergedTree = true).assertCountEquals(1)
        for (title in listOf("第一次练习赛", "第二次练习赛", "第三次练习赛")) rule.onNodeWithText(title, useUnmergedTree = true).assertDoesNotExist()
        capture("single-practice-calendar")
            rule.onAllNodesWithTag("race-${upcoming.id}").assertCountEquals(1)
            capture("calendar")
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.chooseScheduleSeason("f1-season", 2020)
        rule.scrollToScheduleTag("race-f1-2020-1")
        rule.onNodeWithTag("race-f1-2020-1").performClick()
        val race = archive.read("https://api.jolpi.ca/ergast/f1/2020.json?limit=100") { F1Api.parseSeason(it, 2020) }!!.data.first()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("练习赛"))
        rule.onAllNodesWithText("练习赛").assertCountEquals(1)
        for (title in listOf("第一次练习赛", "第二次练习赛", "第三次练习赛")) rule.onNodeWithText(title).assertDoesNotExist()
        capture("single-practice-detail")
        val q = archive.read("https://api.jolpi.ca/ergast/f1/2020/1/qualifying.json?limit=100") { F1QualifyingRepository.parse(it, race) }!!.data
        rule.scrollToScheduleTag("f1-detail-section-1")
        rule.onNodeWithTag("f1-detail-section-1").performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithTag("f1-qualifying-${q.first().driverId}").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(q.first().q3!!).assertExists()
        assertTeamColor("f1-qualifying-${q.first().driverId}-team-color", 0xFF27F4D2.toInt())
        rule.onAllNodes(hasContentDescription("车手照片", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("Q1", substring = true)).assertCountEquals(0)
        capture("qualifying")
        rule.scrollToScheduleTag("f1-qualifying-${q.last().driverId}")
        rule.onNodeWithTag("f1-qualifying-${q.last().driverId}").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.scrollToScheduleTag("f1-detail-section-2")
        rule.onNodeWithTag("f1-detail-section-2").performClick()
        val results = runBlocking { FootballStore(PitPitchDatabase.build(rule.activity)).f1Results(2020, 1).first }
        assertEquals(20, results.size)
        val last = results.last()
        rule.scrollToScheduleTag("f1-race-result-2020-${last.driverId}")
        rule.onNodeWithTag("f1-race-result-2020-${last.driverId}-points").assertTextEquals("0")
        rule.onAllNodesWithText("退赛").onLast().assertExists()
        rule.onAllNodes(hasContentDescription("车手照片", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("Electronics", substring = true)).assertCountEquals(0)
        capture("retirement")
    }
    @Test fun onePointLappedResultUsesRealGapAndAlpineTeamColor() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-2").performClick()
        rule.onNodeWithTag("f1-section-0").performClick()
        rule.chooseScheduleSeason("f1-season", 2026)
        rule.scrollToScheduleTag("race-f1-2026-1")
        rule.onNodeWithTag("race-f1-2026-1").performClick()
        rule.scrollToScheduleTag("f1-detail-section-2")
        rule.onNodeWithTag("f1-detail-section-2").performClick()
        rule.scrollToScheduleTag("f1-race-result-2026-gasly")
        rule.onNodeWithTag("f1-race-result-2026-gasly-points").assertTextEquals("1")
        rule.onNodeWithTag("f1-race-result-2026-gasly").onChildren().filter(hasText("退赛")).assertCountEquals(0)
        rule.onNodeWithText("+29.027").assertExists()
        assertTeamColor("f1-race-result-2026-gasly-team-color", 0xFF00A1E8.toInt())
        capture("lapped-one-point")
    }
    private fun assertTeamColor(tag: String, expected: Int) {
        val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(rule.activity.cacheDir, "pitpitch-validation/f1-complete-$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
