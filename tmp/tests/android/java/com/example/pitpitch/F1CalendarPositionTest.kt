package com.example.pitpitch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.F1Race
import com.example.pitpitch.data.F1Session
import com.example.pitpitch.data.displaySessions
import com.example.pitpitch.feature.F1Screen
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

class F1CalendarPositionTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneId.of("UTC")
    private val capturedInstant = Instant.now()
    private val screen = mutableStateOf(SportsViewModel.State())
    private var density = 1f

    private fun races(year: Int, focusRound: Int, sprint: Boolean = false): List<F1Race> {
        val today = capturedInstant.atZone(zone).toLocalDate()
        return (1..15).map { round ->
            val date = today.plusDays(2L + (round - focusRound) * 7L)
            val friday = date.minusDays(2).toString()
            val saturday = date.minusDays(1).toString()
            F1Race(year, round, "Fixture Grand Prix $round", "Fixture Circuit $round",
                date.toString(), "14:00:00Z", "Bahrain",
                sessions = listOf(
                    F1Session("第一次练习赛", friday, "10:30:00Z"),
                    F1Session("第二次练习赛", friday, "14:00:00Z"),
                    F1Session("第三次练习赛", saturday, "10:00:00Z"),
                    F1Session("排位赛", saturday, "14:00:00Z")
                ) + if (sprint) listOf(F1Session("冲刺赛", saturday, "11:00:00Z")) else emptyList())
        }
    }

    private fun state(year: Int, focusRound: Int, sprint: Boolean = false) =
        SportsViewModel.State(season = year, calendars = mapOf(year to races(year, focusRound, sprint)))

    private fun render(height: Dp = 560.dp) {
        rule.setContent {
            density = LocalDensity.current.density
            val destinations = rememberSaveableStateHolder()
            var calendarVisible by remember { mutableStateOf(true) }
            MaterialTheme(colorScheme = SportsColors) {
                Box(Modifier.fillMaxWidth().height(height)) {
                    if (calendarVisible) destinations.SaveableStateProvider("calendar") {
                        F1Screen(screen.value, zone, { calendarVisible = false }, {},
                            { year -> screen.value = screen.value.copy(season = year, loading = true) }, {})
                    } else TextButton(onClick = { calendarVisible = true }) { Text("Return to calendar") }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun listViewport(): Rect = bounds("f1-list-viewport")

    private fun assertCentered(race: F1Race) {
        val tag = "race-${race.id}"
        rule.waitUntil(10000) {
            val row = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            row != null && abs(row.center.y - listViewport().center.y) <= 2f
        }
        val viewport = listViewport()
        val row = bounds(tag)
        assertEquals("Complete weekend must be centered in the list viewport", viewport.center.y, row.center.y, 2f)
        assertTrue("Expanded weekend must fit without top clipping", row.top >= viewport.top)
        assertTrue("Expanded weekend must fit without bottom clipping", row.bottom <= viewport.bottom)
        rule.onNodeWithTag("f1-calendar-expanded-${race.id}", useUnmergedTree = true).assertIsDisplayed()
        for (round in listOf(race.round - 1, race.round + 1)) {
            rule.onNodeWithTag("race-f1-${race.season}-$round").assertIsDisplayed()
            val neighbor = bounds("race-f1-${race.season}-$round")
            assertTrue("Both neighboring races must occupy visible viewport space", neighbor.height > 0f && neighbor.bottom > viewport.top && neighbor.top < viewport.bottom)
        }
    }

    @Test fun middleWeekendCentersItsActualHeightForNormalAndSprintSessions() {
        screen.value = state(2026, 8).copy(loading = true)
        render()
        val normal = screen.value.races[7]
        assertCentered(normal)
        rule.onAllNodesWithText("练习赛", useUnmergedTree = true).assertCountEquals(1)
        normal.displaySessions(includeRace = true).forEach { session ->
            rule.onNodeWithText(session.localTime(zone), useUnmergedTree = true).assertIsDisplayed()
        }
        rule.runOnUiThread { screen.value = state(2025, 8, sprint = true) }
        val sprint = screen.value.races[7]
        assertCentered(sprint)
    }

    @Test fun firstRaceUsesTheRealListStartWithoutArtificialCenterPadding() {
        screen.value = state(2026, 1)
        render()
        rule.waitForIdle()
        val viewport = listViewport()
        val title = rule.onNodeWithTag("f1-schedule-title").fetchSemanticsNode().boundsInRoot
        val first = bounds("race-f1-2026-1")
        assertTrue(first.top >= title.bottom)
        assertTrue("First race must remain near the beginning rather than be centered", first.center.y < viewport.center.y - 10f)
        val scroll = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, scroll.value(), .01f)
    }

    @Test fun lastRaceUsesTheRealListEndIncludingRefreshFooter() {
        screen.value = state(2026, 15)
        render()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("race-f1-2026-15").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val viewport = listViewport()
        val footer = rule.onNode(hasText("刷新") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val last = bounds("race-f1-2026-15")
        assertTrue(last.bottom <= footer.top)
        assertTrue("Last race must sit at the end rather than in an invented centered gap", last.center.y > viewport.center.y + 10f)
        val scroll = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(scroll.maxValue(), scroll.value(), .01f)
    }

    @Test fun historicalSeasonShowsTheFullCompactListWithoutExpandingItsLastRace() {
        screen.value = state(2025, 16)
        render()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("race-f1-2025-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("race-f1-2025-1").assertIsDisplayed()
        val scroll = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Historical season must open at its first race", 0f, scroll.value(), .01f)
        for (race in screen.value.races) {
            rule.scrollToScheduleTag("race-${race.id}")
            rule.onNodeWithTag("race-${race.id}").assertIsDisplayed()
            rule.onNodeWithTag("f1-calendar-expanded-${race.id}", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun directSeasonSwitchAndRestorationCannotPaintRowsAcrossTheFixedControls() {
        screen.value = state(2026, 8)
        render()
        assertCentered(screen.value.races[7])
        val controls = bounds("f1-controls")
        val seasonLeft = bounds("f1-season").left
        val reference = rule.onNodeWithTag("f1-controls").captureToImage().toPixelMap()
        val width = (seasonLeft - controls.left).toInt()
        for (year in listOf(2025, 2026, 2025)) {
            rule.onNodeWithTag("f1-season").performClick()
            rule.onNodeWithTag("f1-season-option-$year").performClick()
            rule.runOnUiThread { screen.value = state(year, if (year == 2026) 8 else 16) }
            rule.waitForIdle()
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(7)
            rule.waitForIdle()
            val actual = rule.onNodeWithTag("f1-controls").captureToImage().toPixelMap()
            for (y in 0 until reference.height) for (x in 0 until width) {
                assertEquals("List pixels must remain outside fixed controls after $year switch at $x,$y",
                    reference[x, y], actual[x, y])
            }
            assertTrue("Viewport must remain below the fixed toolbar", listViewport().top >= controls.bottom)
        }
    }

    @Test fun delayedCalendarAndSeasonChangeCenterOnlyAfterTheirDataArrivesWithErrorOffset() {
        screen.value = SportsViewModel.State(season = 2026, loading = true)
        render()
        rule.runOnUiThread { screen.value = state(2026, 6).copy(loading = true) }
        rule.waitForIdle()
        rule.runOnUiThread { screen.value = screen.value.copy(loading = false) }
        assertCentered(screen.value.races[5])
        rule.chooseScheduleSeason("f1-season", 2025)
        rule.runOnUiThread { screen.value = state(2025, 9, sprint = true).copy(loading = true, error = "Cached schedule") }
        rule.waitForIdle()
        rule.runOnUiThread { screen.value = screen.value.copy(loading = false) }
        assertCentered(screen.value.races[8])
    }

    @Test fun manualPositionSurvivesRefreshSectionAndDisposedNavigationReturn() {
        screen.value = state(2026, 8)
        render()
        assertCentered(screen.value.races[7])
        val tag = "race-f1-2026-2"
        rule.scrollToScheduleTag(tag)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        val before = bounds(tag)
        rule.runOnUiThread { screen.value = screen.value.copy(loading = true) }
        rule.waitForIdle()
        assertEquals(before.top, bounds(tag).top, 2f)
        rule.runOnUiThread { screen.value = state(2026, 11) }
        rule.waitForIdle()
        assertEquals("Refresh must not refocus the new upcoming race", before.top, bounds(tag).top, 2f)
        rule.onNodeWithTag("f1-section-1").performClick()
        rule.onNodeWithTag("f1-section-0").performClick()
        assertEquals("Section return must retain manual position", before.top, bounds(tag).top, 2f)
        rule.onNodeWithTag(tag).performClick()
        rule.onNodeWithText("Return to calendar").performClick()
        assertEquals("Navigation disposal/restoration must retain manual position", before.top, bounds(tag).top, 2f)
        rule.runOnUiThread { screen.value = state(2025, 8) }
        assertCentered(screen.value.races[7])
        rule.runOnUiThread { screen.value = state(2026, 11) }
        assertEquals("Revisiting a season must not repeat its initial focus", before.top, bounds(tag).top, 2f)
    }

    @Test fun oversizedWeekendKeepsItsHeadingAtUsableTopInsteadOfClippingItForCentering() {
        screen.value = state(2026, 8, sprint = true)
        render(height = 300.dp)
        val tag = "race-f1-2026-8"
        rule.waitUntil(10000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val viewport = listViewport()
        val fullRow = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("Fixture must exceed the available list height", (fullRow.bottom - fullRow.top).value * density > viewport.height)
        assertTrue("Oversized row must not hide its heading above the viewport", fullRow.top.value * density >= viewport.top - 2f)
        assertTrue("Oversized row must stay near the start rather than be centered with a clipped heading", fullRow.top.value * density < viewport.center.y)
        rule.onNodeWithText("Fixture Grand Prix 8", useUnmergedTree = true).assertIsDisplayed()
    }
}
