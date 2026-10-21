package com.example.pitpitch

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.CalendarRaceRow
import com.example.pitpitch.feature.RaceCard
import com.example.pitpitch.feature.RaceDetail
import com.example.pitpitch.feature.RaceWeekendCard
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class F1CalendarLocalizationTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun calendar(year: Int) = SportsArchive(context).read(
        "https://api.jolpi.ca/ergast/f1/$year.json?limit=100") { F1Api.parseSeason(it, year) }!!.data

    @Test fun everyArchivedCalendarHasChineseRaceAndCircuitLabelsWithoutChangingFacts() {
        val races = f1ArchiveYears().flatMap { calendar(it) }
        assertTrue(races.isNotEmpty())
        for (race in races) {
            val original = race.copy()
            val title = homeRaceName(race.name)
            val circuit = f1CircuitName(race)
            assertTrue("${race.id}: ${race.name}", title.contains(Regex("[\\u4e00-\\u9fff]")))
            assertFalse("${race.id}: $title", title.contains(Regex("[A-Za-z]")))
            assertTrue("${race.id}: ${race.circuit}", circuit.contains(Regex("[\\u4e00-\\u9fff]")))
            assertFalse("${race.id}: $circuit", circuit.contains(Regex("[A-Za-z]")))
            assertEquals(original, race)
            assertEquals(circuit, f1CircuitName(race.copy(circuitId = null)))
        }
        assertEquals("巴林大奖赛（马来西亚）", homeRaceName("Bahrain Grand Prix in Malaysia"))
        assertEquals("巴西大奖赛", homeRaceName("Brazilian Grand Prix"))
        assertEquals("圣保罗大奖赛", homeRaceName("São Paulo Grand Prix"))
        assertEquals("Unpublished Grand Prix", homeRaceName("Unpublished Grand Prix"))
        val future = races.first().copy(circuitId = "unknown", circuit = "Unpublished circuit")
        assertEquals(future.circuit, f1CircuitName(future))
    }

    @Test fun screenshotRowsAndEveryDisplayEntryUseTheSameChineseLabels() {
        val races = calendar(2026).filter { it.round in 16..20 }
        val malaysia = races.single { it.name == "Bahrain Grand Prix in Malaysia" }
        val brazil = races.single { it.name == "Brazilian Grand Prix" }
        val mode = mutableIntStateOf(0)
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            when (mode.intValue) {
                0 -> LazyColumn { items(races, key = { it.id }) {
                    CalendarRaceRow(it, zone, expanded = it.round == 18, open = {})
                } }
                1 -> RaceWeekendCard(malaysia, zone, homePreview = true, open = {})
                2 -> RaceDetail(malaysia, zone, null, {}, null, cached = true)
                else -> RaceCard(malaysia, zone, null, {}, null)
            }
        } }
        for (race in listOf(malaysia, brazil)) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("race-${race.id}"))
            rule.onNodeWithText(homeRaceName(race.name), useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithText(f1CircuitName(race), useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithText(race.name, useUnmergedTree = true).assertDoesNotExist()
            rule.onNodeWithText(race.circuit, useUnmergedTree = true).assertDoesNotExist()
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val screenshot = java.io.File(context.cacheDir, "pitpitch-validation/f1-calendar-chinese.png")
        screenshot.parentFile!!.mkdirs()
        screenshot.outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        for (entry in 1..3) {
            rule.runOnIdle { mode.intValue = entry }
            rule.onNodeWithText(homeRaceName(malaysia.name), useUnmergedTree = true).assertExists()
            rule.onNodeWithText(f1CircuitName(malaysia), substring = true, useUnmergedTree = true).assertExists()
            rule.onNodeWithText(malaysia.name, useUnmergedTree = true).assertDoesNotExist()
            rule.onNodeWithText(malaysia.circuit, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
