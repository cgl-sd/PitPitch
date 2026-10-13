package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import com.example.pitpitch.feature.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class StartupPresentationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun initialHomeShowsNormalTitleWithoutLoadingOrFalseEmptyState() {
        val state = SportsViewModel.State(ready = false, footballLoading = true, loading = true)
        rule.setContent { MaterialTheme { HomeScreen(state, ZoneId.systemDefault(), {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("赛事赛程").assertExists()
        rule.onAllNodes(hasText("读取", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("刷新", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("暂无", substring = true)).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }
    @Test fun refreshKeepsScrolledScheduleAndRoundStable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = SportsArchive(context)
        val season = runBlocking { EuropeanFootballRepository(JsonClient(archive = archive), archive).season(2026) }
        val state = mutableStateOf(SportsViewModel.State(ready = true, footballSeason = 2026, matches = season.matches))
        rule.setContent { MaterialTheme { FootballScreen(state.value, ZoneId.systemDefault(), {}) } }
        val fixtures = season.matches.filter { it.competitionId == "eng.1" }.sortedBy { homeMatchTime(it, ZoneId.systemDefault()) }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        val tag = "match-${fixtures[20].id}"
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val round = rule.onNodeWithTag("football-round").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        rule.runOnIdle { state.value = state.value.copy(footballLoading = true) }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        assertEquals(bounds, rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { state.value = state.value.copy(footballLoading = false) }
        assertEquals(bounds, rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        assertEquals(round, rule.onNodeWithTag("football-round").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text])
    }
}
