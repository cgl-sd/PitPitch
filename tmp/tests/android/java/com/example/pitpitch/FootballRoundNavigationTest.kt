package com.example.pitpitch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.sampleMatches
import com.example.pitpitch.feature.FootballScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Controlled fixtures only inside the test; production continues to use the true archive. */
@RunWith(AndroidJUnit4::class)
class FootballRoundNavigationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun nextFixtureIsInitialAnchorAndRoundControlsJumpToFirstFixture() {
        val now = Instant.now()
        fun fixture(id: String, round: Int, hours: Long, finished: Boolean = false) = sampleMatches.first().copy(
            id = id, competition = "英超", competitionId = "eng.1", stage = "第 $round 轮",
            kickoffUtc = now.plusSeconds(hours * 3600).toString(), status = if (finished) "完场" else "待赛", isDemo = false)
        val matches = buildList {
            add(fixture("previous", 1, -100, true))
            repeat(15) { add(fixture("earlier-$it", 2, -50L + it, true)) }
            add(fixture("next", 2, 2))
            repeat(12) { add(fixture("future-$it", 2, 3L + it)) }
            add(fixture("later-round", 3, 100))
            // Keep enough content after the target to align it at the top on all device sizes.
            repeat(12) { add(fixture("last-round-$it", 3, 101L + it)) }
        }
        rule.setContent { FootballScreen(SportsViewModel.State(matches = matches), ZoneId.systemDefault(), {}) }
        rule.onNodeWithTag("football-refresh").assertDoesNotExist()
        rule.onNodeWithTag("football-search").assertDoesNotExist()
        rule.onNodeWithTag("football-filters").assertDoesNotExist()
        rule.onNodeWithTag("match-next").assertIsDisplayed()
        rule.onNodeWithTag("football-round").assertTextContains("第 2 轮")
        rule.onNodeWithTag("football-round").performClick()
        rule.onNodeWithTag("match-earlier-0").assertIsDisplayed()
        rule.onNodeWithTag("football-next-round").performClick()
        rule.onNodeWithTag("football-round").assertTextContains("第 3 轮")
        rule.onNodeWithTag("match-later-round").assertIsDisplayed()
        rule.onNodeWithTag("football-next-round").assertIsNotEnabled()
        rule.onNodeWithTag("football-prev-round").performClick()
        rule.onNodeWithTag("match-earlier-0").assertIsDisplayed()
        rule.onNodeWithTag("football-prev-round").performClick()
        rule.onNodeWithTag("match-previous").assertIsDisplayed()
        rule.onNodeWithTag("football-prev-round").assertIsNotEnabled()
    }
}
