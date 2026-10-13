package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.*
import com.example.pitpitch.ui.SportsColors
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

class F1ProgressTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun state(year: Int): SportsViewModel.State {
        val archive = SportsArchive(context)
        val base = "https://api.jolpi.ca/ergast/f1/$year"
        return SportsViewModel.State(season = year,
            calendars = mapOf(year to archive.read("$base.json?limit=100") { F1Api.parseSeason(it, year) }!!.data),
            drivers = archive.read("$base/driverstandings.json?limit=100") { JolpicaAdapter.standings(it, false, year) },
            constructors = archive.read("$base/constructorstandings.json?limit=100") { JolpicaAdapter.standings(it, true, year) })
    }
    @Test fun everyArchivedRaceCollapsesPracticeWithoutMutatingSource() {
        var checked = 0
        for (year in 2020..2026) for (race in state(year).races) {
            val original = race.sessions.toList()
            val practices = original.filter { it.displayTitle() == "练习赛" }
            for (home in listOf(false, true)) {
                val visible = race.displaySessions(includeRace = true, homePreview = home)
                org.junit.Assert.assertEquals(if (practices.isEmpty()) 0 else 1, visible.count { it.displayTitle() == "练习赛" })
                visible.filter { it.title != "正赛" }.forEach { org.junit.Assert.assertTrue(original.contains(it)) }
                org.junit.Assert.assertEquals(original, race.sessions)
            }
            checked++
        }
        org.junit.Assert.assertTrue(checked > 140)
    }
    @Test fun homeWeekendShowsSinglePracticeAndPublishedSessions() {
        val race = state(2026).races.first()
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            RaceWeekendCard(race, ZoneId.systemDefault(), homePreview = true) {}
        } }
        rule.onAllNodesWithText("练习赛").assertCountEquals(1)
        for (title in listOf("第一次练习赛", "第二次练习赛", "第三次练习赛")) rule.onNodeWithText(title).assertDoesNotExist()
        rule.onNodeWithText(race.displaySessions().first { it.displayTitle() == "练习赛" }.localTime(ZoneId.systemDefault())).assertExists()
        capture("f1-single-practice-home")
        rule.onNodeWithTag("f1-weekend-detail").assertDoesNotExist()
        rule.onNodeWithText(homeRaceName(race.name)).assertExists()
    }
    @Test fun bothAnnualStandingsUseTheirPublishedRoundAndSelectedSeasonCalendar() {
        val s = mutableStateOf(state(2026))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            F1Screen(s.value, ZoneId.systemDefault(), {}, {}, { s.value = state(it) }, {})
        } }
        rule.onNodeWithTag("f1-all").assertDoesNotExist()
        rule.onNodeWithText("未开赛").assertDoesNotExist()
        rule.onNodeWithText("已开赛").assertDoesNotExist()
        val focus = f1CalendarFocus(s.value.races, java.time.Instant.now(), ZoneId.systemDefault())!!
        rule.waitUntil(10000) { rule.onAllNodesWithTag("race-${focus.id}").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("race-${focus.id}").assertIsDisplayed()
        capture("f1-calendar-default-focus")
        for (section in 1..2) {
            rule.onNodeWithTag("f1-section-$section").performClick()
            rule.onNodeWithTag("f1-standing-round").assertDoesNotExist()
            val response = if (section == 1) s.value.drivers else s.value.constructors
            val progress = f1SeasonProgress(2026, s.value.races, response!!.data.round)!!
            rule.onNodeWithText("${progress.counted}/${progress.total}").assertIsDisplayed()
            rule.onNodeWithText("2026 赛季").assertDoesNotExist()
            rule.onNodeWithText("赛季进度").assertDoesNotExist()
            rule.onNodeWithText("车手 / 车队").assertDoesNotExist()
            rule.onNodeWithText(if (section == 1) "车手" else "车队").assertIsDisplayed()
            rule.onAllNodes(hasText("还剩", substring = true)).assertCountEquals(0)
            rule.onAllNodes(hasText("已计分", substring = true)).assertCountEquals(0)
            rule.onNodeWithTag("f1-season-progress-bar").assertRangeInfoEquals(
                androidx.compose.ui.semantics.ProgressBarRangeInfo(progress.fraction, 0f..1f))
            capture("f1-progress-${if (section == 1) "drivers" else "constructors"}")
        }
        rule.onNodeWithTag("f1-season").performClick()
        rule.onNodeWithTag("f1-season-option-2020").performClick()
        rule.onNodeWithText("17/17").assertIsDisplayed()
        rule.runOnUiThread { s.value = s.value.copy(constructors = null) }
        rule.onNodeWithText("赛季进度暂未提供").assertDoesNotExist()
        rule.onNodeWithTag("f1-season-progress-bar").assertDoesNotExist()
    }
    private fun capture(name: String) {
        val file = File(context.cacheDir, "pitpitch-validation/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
