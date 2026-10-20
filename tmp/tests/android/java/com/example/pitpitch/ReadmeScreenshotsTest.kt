package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Capture installed UI for README artwork; never synthesizes scores, people or fixtures. */
class ReadmeScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun shown(tag: String) = rule.waitUntil(30000) {
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        // Let the native ripple and navigation compositor finish before taking a promotional still.
        android.os.SystemClock.sleep(700)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(rule.activity.cacheDir, "pitpitch-validation/readme-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun captureCurrentHomeFootballTeamAndF1Pages() {
        shown("nav-0")
        rule.onNodeWithTag("nav-0").performClick()
        rule.waitUntil(30000) {
            rule.onAllNodes(hasContentDescription("场地照片", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        capture("home")
        rule.onNodeWithTag("nav-1").performClick()
        rule.activityRule.scenario.onActivity {
            ViewModelProvider(it)[SportsViewModel::class.java].footballCompetition("eng.1")
        }
        shown("football-section-0")
        rule.onNodeWithTag("football-section-0").performClick()
        shown("football-round")
        capture("football-schedule")
        rule.onNodeWithTag("football-section-1").performClick()
        shown("standing-1000360")
        capture("football-standings")
        rule.onNodeWithTag("nav-team").performClick()
        shown("team-profile")
        rule.waitUntil(30000) {
            val s = ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value
            !s.footballLoading && s.matches.isNotEmpty()
        }
        capture("team")
        rule.onNodeWithContentDescription("返回上一级").performClick()
        rule.onNodeWithTag("nav-2").performClick()
        shown("f1-section-0")
        rule.chooseScheduleSeason("f1-season", 2026)
        rule.onNodeWithTag("f1-section-0").performClick()
        rule.waitUntil(30000) {
            rule.onAllNodes(hasContentDescription("赛道布局图", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val races = ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.races.sortedBy { it.round }
        val upcoming = f1UpcomingRace(races, java.time.Instant.now(), java.time.ZoneId.systemDefault())
        if (upcoming != null) rule.scrollToScheduleTag("race-${upcoming.id}")
        capture("f1-calendar")
        rule.onNodeWithTag("f1-section-1").performClick()
        rule.waitUntil(30000) {
            rule.onAllNodes(hasContentDescription("车手照片", substring = true)).fetchSemanticsNodes().size >= 3
        }
        capture("f1-drivers")
        rule.onNodeWithTag("f1-section-2").performClick()
        rule.waitUntil(30000) {
            rule.onAllNodes(hasContentDescription("2026 赛季赛车", substring = true)).fetchSemanticsNodes().size >= 3
        }
        capture("f1-constructors")
        rule.onNodeWithTag("nav-0").performClick()
    }
}
