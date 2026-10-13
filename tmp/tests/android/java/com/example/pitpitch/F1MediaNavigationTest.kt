package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.compose.ui.unit.dp
import com.example.pitpitch.data.db.PitPitchDatabase
import com.example.pitpitch.data.db.FootballStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*

class F1MediaNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun installedAppRetainsPhotosForEveryArchivedSeason() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-2").performClick()
        for (year in 2020..2026) {
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            rule.onNodeWithTag("f1-section-0").performClick()
            rule.chooseScheduleSeason("f1-season", year)
            for ((section, kind) in listOf(2 to "constructor", 1 to "driver")) {
                rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                rule.onNodeWithTag("f1-section-$section").performClick()
                val entry = runBlocking { FootballStore(PitPitchDatabase.build(rule.activity)).f1Standings(year, kind, null)!!.first.entries.first() }
                val description = if (kind == "constructor") "${entry.name} $year 赛季赛车" else "${entry.name} 车手照片"
                try {
                    rule.waitUntil(20000) { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
                } catch (error: Throwable) {
                    capture("f1-history-failed-$year-$kind")
                    throw AssertionError("Missing $description; ${rule.onRoot().printToString()}", error)
                }
                if (kind == "constructor") {
                    rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("${entry.name} 车队标识").fetchSemanticsNodes().isNotEmpty() }
                }
                rule.onNodeWithText(entry.points).assertExists()
                capture("f1-history-$year-$kind")
            }
        }
        rule.onNodeWithTag("nav-0").performClick()
    }
    @Test fun installedStandingsKeepProviderOrderPointsAndSeasonBoundImages() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-2").performClick()
        rule.chooseScheduleSeason("f1-season", 2026)
        for ((section, kind) in listOf(1 to "driver", 2 to "constructor")) {
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            rule.onNodeWithTag("f1-section-$section").performClick()
            val rows = runBlocking { FootballStore(PitPitchDatabase.build(rule.activity)).f1Standings(2026, kind, null)!!.first.entries }
            assertEquals(rows.size, rows.map { if (kind == "driver") it.driverId else it.constructorId }.distinct().size)
            assertEquals(rows.map { it.position.toInt() }.sorted(), rows.map { it.position.toInt() })
            rule.waitUntil(20000) { rule.onAllNodesWithText(rows[2].name).fetchSemanticsNodes().isNotEmpty() }
            val tops = rows.take(3).map { rule.onNodeWithText(it.name).fetchSemanticsNode().boundsInRoot.top }
            assertTrue(tops.zipWithNext().all { (a, b) -> a < b })
            val entity = if (kind == "driver") rows.first().driverId else rows.first().constructorId
            val media = rule.onNodeWithTag("f1-media-${if (kind == "driver") "driver" else "logo"}-2026-$entity")
            rule.onNodeWithTag("f1-$kind-2026-$entity-points").assertTextEquals(rows.first().points)
            media.assertIsDisplayed()
            if (kind == "constructor") {
                rule.onAllNodes(hasText("获胜", substring = true)).assertCountEquals(0)
                val car = rule.onNodeWithTag("f1-media-car-2026-${rows.first().constructorId}")
                val logo = rule.onNodeWithTag("f1-media-logo-2026-${rows.first().constructorId}")
                assertTrue(car.fetchSemanticsNode().boundsInRoot.left > logo.fetchSemanticsNode().boundsInRoot.right)
            }
            rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("${rows.first().name} ${if (kind == "driver") "车手照片" else "车队标识"}").fetchSemanticsNodes().isNotEmpty() }
            capture("f1-standings-app-$kind")
        }
        rule.onNodeWithTag("nav-0").performClick()
    }
    @Test fun installedAppShowsVenueAndRaceDetailPhotosWithExistingNavigation() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-2").performClick()
        rule.waitUntil(20000) { rule.onAllNodesWithTag("f1-section-0").fetchSemanticsNodes().isNotEmpty() }
        rule.chooseScheduleSeason("f1-season", 2026)
        rule.scrollToScheduleTag("race-f1-2026-1")
        rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("Albert Park Grand Prix Circuit 赛道布局图").fetchSemanticsNodes().isNotEmpty() }
        capture("f1-media-app-calendar")
        rule.scrollToScheduleTag("race-f1-2026-1")
        rule.onNodeWithTag("race-f1-2026-1").performClick()
        rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("Albert Park Grand Prix Circuit 赛道布局图").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("f1-detail-section-0").assertIsSelected()
        capture("f1-media-app-detail")
        rule.onNodeWithContentDescription("返回上一级").performClick()
        rule.onNodeWithTag("nav-0").performClick()
        rule.waitUntil(20000) { rule.onAllNodes(hasContentDescription("场地照片", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("f1-media-app-home")
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(rule.activity.cacheDir, "pitpitch-validation/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
