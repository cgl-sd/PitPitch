package com.example.pitpitch

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArchiveStartYearTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun footballAndF1SelectorsBeginAt2020AndRejectEarlierRequests() {
        rule.waitUntil(15000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-1").performClick()
        rule.onNodeWithTag("football-season").performClick()
        rule.onNodeWithTag("football-season-option-2020").assertExists()
        for (year in 2017..2019) rule.onNodeWithTag("football-season-option-$year").assertDoesNotExist()
        capture("football-seasons-from-2020")
        rule.onNodeWithTag("football-season-option-2020").performClick()
        rule.waitUntil(20000) { !ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.footballLoading }
        rule.onNodeWithTag("nav-2").performClick()
        rule.onNodeWithTag("f1-season").performClick()
        rule.onNodeWithTag("f1-season-option-2020").assertExists()
        for (year in 2017..2019) rule.onNodeWithTag("f1-season-option-$year").assertDoesNotExist()
        capture("f1-seasons-from-2020")
        rule.onNodeWithTag("f1-season-option-2020").performClick()
        rule.waitUntil(20000) { !ViewModelProvider(rule.activity)[SportsViewModel::class.java].state.value.loading }
        rule.activityRule.scenario.onActivity {
            val model = ViewModelProvider(it)[SportsViewModel::class.java]
            assertEquals(2020, model.state.value.footballSeason)
            assertEquals(2020, model.state.value.season)
            assertEquals(17, model.state.value.races.size)
            model.footballSeason(2019); model.season(2019)
            assertEquals(2020, model.state.value.footballSeason)
            assertEquals(2020, model.state.value.season)
        }
    }
    @Test fun legacyCachedSeasonsStayStoredButCannotEnterVisibleRaceData() {
        val name = "pitpitch_archive_start_validation"
        val preferences = rule.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        val cache = RaceCache(preferences)
        try {
            cache.saveSeason(2019, listOf(F1Race(2019, 1, "Legacy", "Track", "2019-03-17", null, "")))
            cache.saveSeason(2020, listOf(F1Race(2020, 1, "Supported", "Track", "2020-07-05", null, "")))
            assertEquals(listOf(2020), cache.allCachedRaces().map { it.season })
            assertTrue(preferences.contains("f1_calendar_2019"))
        } finally { rule.activity.deleteSharedPreferences(name) }
    }
    private fun capture(name: String) {
        val file = File(rule.activity.cacheDir, "pitpitch-validation/$name.png")
        file.parentFile!!.mkdirs()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
