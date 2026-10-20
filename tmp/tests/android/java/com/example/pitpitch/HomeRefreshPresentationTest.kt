package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.HomeScreen
import com.example.pitpitch.feature.F1Screen
import com.example.pitpitch.ui.SportsColors
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeRefreshPresentationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun pullingAndProgrammaticRefreshMoveContentAndThenSettleWithoutChangingLayout() {
        val state = mutableStateOf(SportsViewModel.State(homeReady = true, ready = true,
            hiddenHomeCompetitions = footballCompetitions.map { it.id }.toSet() + "f1"))
        var refreshes = 0
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            HomeScreen(state.value, ZoneId.of("Asia/Shanghai"), {}, {}, {}, {}, {}, onRefresh = {
                refreshes++; state.value = state.value.copy(homeRefreshing = true)
            })
        } }
        val original = rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("home-refresh").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertEquals(1, refreshes)
        assertTrue(rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top > original)
        rule.runOnIdle { state.value = state.value.copy(homeRefreshing = false) }
        rule.waitForIdle()
        assertEquals(original, rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.runOnIdle { state.value = state.value.copy(homeRefreshing = true) }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top > original)
        rule.runOnIdle { state.value = state.value.copy(homeRefreshing = false) }
        rule.waitForIdle()
        assertEquals(original, rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun constructorFailureKeepsCachedRowsWithoutAnyUpdatedFooter() {
        val ranking = F1Rankings("1", listOf(RankingEntry("1", "Team", "Team", "20", "", constructorId = "team")))
        val state = SportsViewModel.State(season = 2026, constructors = RemoteSnapshot(ranking, "2026-10-20T01:23:00Z", true, "offline"), rankingError = "offline")
        rule.setContent { MaterialTheme(colorScheme = SportsColors) { F1Screen(state, ZoneId.of("Asia/Shanghai"), {}, {}, {}, {}) } }
        rule.onNodeWithTag("f1-section-2").performClick()
        rule.onNodeWithText("积分更新未成功").assertExists()
        rule.onNodeWithText("offline").assertExists()
        rule.onNodeWithText("20").assertExists()
        rule.onNodeWithTag("f1-constructors-updated").assertDoesNotExist()
        rule.onAllNodesWithText("上次更新：", substring = true).assertCountEquals(0)
    }
    @Test fun homeFooterUsesSuccessfulSourceTimeAndKeepsItDuringRefresh() {
        val state = mutableStateOf(SportsViewModel.State(homeReady = true, ready = true,
            homeUpdatedUtc = "2026-10-20T01:23:00Z",
            hiddenHomeCompetitions = footballCompetitions.map { it.id }.toSet() + "f1"))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            HomeScreen(state.value, ZoneId.of("Asia/Shanghai"), {}, {}, {}, {}, {})
        } }
        rule.onNodeWithTag("home-updated").performScrollTo().assertTextEquals("上次更新：2026-10-20 09:23")
        rule.runOnIdle { state.value = state.value.copy(homeRefreshing = true) }
        rule.onNodeWithTag("home-updated").assertTextEquals("上次更新：2026-10-20 09:23")
        rule.runOnIdle { state.value = state.value.copy(homeRefreshing = false) }
        rule.onNodeWithTag("home-updated").assertTextEquals("上次更新：2026-10-20 09:23")
        rule.runOnIdle { state.value = state.value.copy(homeUpdatedUtc = "2026-10-20T02:45:00Z") }
        rule.onNodeWithTag("home-updated").assertTextEquals("上次更新：2026-10-20 10:45")
    }

}
