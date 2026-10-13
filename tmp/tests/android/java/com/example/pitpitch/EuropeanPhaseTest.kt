package com.example.pitpitch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pitpitch.app.SportsViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** European competitions expose two phases and open the bracket once the league phase has no fixtures left. */
@RunWith(AndroidJUnit4::class)
class EuropeanPhaseTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun viewModel() = ViewModelProvider(rule.activity)[SportsViewModel::class.java]

    @Test fun europeanPhaseControlHasNoQualifyingPhaseAndKnockoutsDropTheRoundRow() {
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-1").performClick()
        rule.activityRule.scenario.onActivity { viewModel().footballSeason(2025) }
        rule.waitUntil(20_000) {
            val state = viewModel().state.value
            state.footballSeason == 2025 && !state.footballLoading && state.matches.isNotEmpty()
        }
        rule.onNodeWithTag("football-leagues").assertExists()
        rule.onNodeWithText("欧冠", useUnmergedTree = true).performClick()
        rule.waitUntil(20_000) {
            val state = viewModel().state.value
            state.footballCompetition == "uefa.champions" && state.matches.any { it.competitionId == "uefa.champions" }
        }
        // Two phases only: the qualifying entry is gone for good.
        rule.onNodeWithText("淘汰赛", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("资格赛/其他", useUnmergedTree = true).assertCountEquals(0)
        // A finished season has no upcoming league fixture, so the bracket is what opens.
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("淘汰赛") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("football-round").assertDoesNotExist()
        // Standings stay available: the phase selector never hides the league table.
        rule.onNodeWithTag("football-section-1").performClick()
        rule.onNodeWithText("淘汰赛不设积分榜").assertDoesNotExist()
    }
}
