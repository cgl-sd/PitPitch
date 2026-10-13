package com.example.pitpitch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import com.example.pitpitch.feature.FootballScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class ContinuousFootballScheduleTest {
    @get:Rule val rule = createComposeRule()
    @Test fun allSevenSchedulesScrollAcrossRealRoundsAndButtonsOnlyJump() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = SportsArchive(context)
        val season = runBlocking { EuropeanFootballRepository(JsonClient(archive = archive), archive).season(2026) }
        val state = mutableStateOf(SportsViewModel.State(footballSeason = 2026, matches = season.matches, footballTables = season.tables))
        val zone = ZoneId.systemDefault()
        rule.setContent { FootballScreen(state.value, zone, {}) }
        for (league in footballCompetitions) {
            rule.runOnIdle { state.value = state.value.copy(footballCompetition = league.id) }
            // Render the new list before sending a scroll action to its semantics node.
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val fixtures = season.matches.filter { it.competitionId == league.id && (!league.european || isEuropeanGroupStage(it.stage)) }
                .sortedBy { homeMatchTime(it, zone) }
            val firstRound = footballRoundKey(fixtures.first())
            val secondIndex = fixtures.indexOfFirst { footballRoundKey(it) != firstRound }
            assertTrue(league.id, secondIndex > 0)
            val secondRound = footballRoundKey(fixtures[secondIndex])
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            rule.onNodeWithTag("football-round").assertTextEquals(footballRoundNavigationLabel(firstRound))
            rule.onNodeWithTag("match-${fixtures.first().id}").assertIsDisplayed()
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(secondIndex)
            rule.onNodeWithTag("football-round").assertTextEquals(footballRoundNavigationLabel(secondRound))
            rule.onNodeWithTag("match-${fixtures[secondIndex].id}").assertIsDisplayed()
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            rule.onNodeWithTag("football-round").assertTextEquals(footballRoundNavigationLabel(firstRound))
            rule.onNodeWithTag("football-next-round").performClick()
            rule.onNodeWithTag("football-round").assertTextEquals(footballRoundNavigationLabel(secondRound))
            rule.onNodeWithTag("football-prev-round").performClick()
            rule.onNodeWithTag("football-round").assertTextEquals(footballRoundNavigationLabel(firstRound))
        }
    }
}
