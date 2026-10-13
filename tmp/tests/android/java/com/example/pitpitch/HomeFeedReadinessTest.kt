package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.sampleMatches
import com.example.pitpitch.feature.HomeScreen
import com.example.pitpitch.ui.SportsColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** The home feed must render football and F1 from the same local pass, never one before the other. */
@RunWith(AndroidJUnit4::class)
class HomeFeedReadinessTest {
    @get:Rule val rule = createComposeRule()

    private fun fixture(id: String) = sampleMatches.first().copy(
        id = id, competition = "英超", competitionId = "eng.1", stage = "第 1 轮", roundNumber = 1,
        status = "待赛", score = "— : —", kickoffUtc = Instant.now().plusSeconds(86_400).toString(),
        date = "2026-10-20", homeTeamId = 1000360, awayTeamId = 1000371, isDemo = false,
    )

    @Test fun homeCardsAppearOnlyOnceBothLocalFeedsAreReady() {
        val state = mutableStateOf(SportsViewModel.State(matches = listOf(fixture("fb-home-1")), ready = true, homeReady = false))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            HomeScreen(state.value, ZoneId.systemDefault(), {}, {}, {}, {}, {})
        } }
        // Preferences and fixtures are still arriving: the page shows its frame but no partial feed.
        rule.onNodeWithText("赛事赛程").assertExists()
        rule.onNodeWithText("英超").assertDoesNotExist()
        rule.onNodeWithText("暂无后续足球轮次").assertDoesNotExist()

        rule.runOnIdle { state.value = state.value.copy(homeReady = true) }
        rule.onNodeWithText("英超").assertExists()
    }
}
