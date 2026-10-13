package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.FootballScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class FootballRealRoundsTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun original(archive: SportsArchive, year: Int): List<FootballMatch> = footballCompetitions.flatMap { league ->
        listOf(year, year + 1).flatMap { calendarYear ->
            archive.read("${EspnFootballAdapter.SITE}${league.id}/scoreboard?dates=$calendarYear&limit=1000") {
                EspnFootballAdapter.matches(it, league, year)
            }!!.data
        }
    }.distinctBy { it.id }
    @Test fun runtimeArchivePreservesOriginalFieldsFrom2020() {
        val archive = SportsArchive(context)
        var count = 0
        for (year in 2020..2026) {
            val metadata = archive.footballRounds(year)
            val original = original(archive, year)
            for (match in original) {
                val result = applyFootballRound(match, year, metadata[match.id])
                assertEquals(match, result.copy(roundNumber = null, roundSource = null))
                if (match.id in metadata) { assertNotNull(result.roundNumber); count++ }
            }
            assertEquals(metadata.size, original.count { it.id in metadata })
        }
        assertEquals(14166, count)
    }
    @Test fun allSevenRealCompetitionsNavigateSourceRounds() {
        val archive = SportsArchive(context)
        val year = 2026
        val metadata = archive.footballRounds(year)
        val matches = original(archive, year).map { applyFootballRound(it, year, metadata[it.id]) }
        val state = mutableStateOf(SportsViewModel.State(matches = matches, footballSeason = year))
        val zone = ZoneId.systemDefault()
        rule.setContent { FootballScreen(state.value, zone, {}, competition = { state.value = state.value.copy(footballCompetition = it) }) }
        for (league in footballCompetitions) {
            rule.onNodeWithText(league.title, useUnmergedTree = true).performScrollTo().performClick()
            rule.waitForIdle()
            val leagueMatches = matches.filter { it.competitionId == league.id }
            val expected = nextFootballMatch(leagueMatches, Instant.now(), zone)?.let(::footballRoundKey)
                ?: footballStages(leagueMatches).last()
            rule.onNodeWithTag("football-round").assertTextContains(expected)
            rule.onNodeWithTag("football-round").performClick()
            val first = leagueMatches.filter { footballRoundKey(it) == expected }.minBy { homeMatchTime(it, zone) }
            rule.onNodeWithTag("match-${first.id}").assertIsDisplayed()
            val screenshot = File(context.cacheDir, "pitpitch-validation/rounds-${league.id}.png")
            screenshot.parentFile!!.mkdirs()
            screenshot.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            rule.onNodeWithTag("football-next-round").performClick()
            val keys = footballStages(leagueMatches)
            rule.onNodeWithTag("football-round").assertTextContains(keys[keys.indexOf(expected) + 1])
            rule.onNodeWithTag("football-prev-round").performClick()
            rule.onNodeWithTag("football-round").assertTextContains(expected)
        }
    }
}
