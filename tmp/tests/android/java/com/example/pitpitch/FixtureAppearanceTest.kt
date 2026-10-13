package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.FootballScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

class FixtureAppearanceTest {
    @get:Rule val rule = createComposeRule()
    @Test fun selectedManchesterUnitedFixtureHasWhiteBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val league = footballCompetitions.first { it.id == "eng.1" }
        val matches = SportsArchive(context).read("${EspnFootballAdapter.SITE}eng.1/scoreboard?dates=2026&limit=1000") {
            EspnFootballAdapter.matches(it, league, 2026)
        }!!.data
        val match = matches.first { it.homeTeamId == 1000360 || it.awayTeamId == 1000360 }
        val team = FootballTeam(1000360, "Manchester United", null)
        rule.setContent { FootballScreen(SportsViewModel.State(homeTeam = team, matches = listOf(match)), ZoneId.systemDefault(), {}) }
        val row = rule.onNodeWithTag("match-${match.id}").assertIsDisplayed().captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.WHITE, row.getPixel(1, 1))
        assertEquals(android.graphics.Color.WHITE, row.getPixel(row.width - 2, row.height / 2))
        val file = File(context.cacheDir, "pitpitch-validation/fixture-no-highlight.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
