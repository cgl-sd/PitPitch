package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.localBackend
import com.example.pitpitch.feature.TeamSelectionScreen
import com.example.pitpitch.ui.SportsColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TeamSelectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun chineseDisplayNamesDetermineInitialAndOrderWithoutRewritingProviderNames() {
        val original = listOf(FootballTeam(1, "AC Milan", null), FootballTeam(2, "Union Berlin", null),
            FootballTeam(3, "Arsenal", null), FootballTeam(4, "AEK Athens", null), FootballTeam(5, "Manchester United", null))
        val rows = teamSelectionEntries(original + original.first(), mapOf(1 to "AC米兰", 2 to "柏林联合", 3 to "阿森纳", 4 to "雅典AEK", 5 to "曼联"))
        assertEquals(5, rows.size)
        assertEquals(listOf("A", "A", "B", "M", "Y"), rows.map { it.initial })
        assertEquals("Y", rows.single { it.team.id == 4 }.initial)
        assertTrue(rows.single { it.team.id == 5 }.matchesTeamSearch("曼"))
        assertTrue(rows.single { it.team.id == 5 }.matchesTeamSearch("MANCHESTER"))
        assertEquals(original.toSet(), rows.map { it.team }.toSet())
    }

    @Test fun initialAppearsOnlyDuringListDragging() {
        val fixtures = (0..39).map { index -> sampleMatches.first().copy(id = "scroll-$index",
            homeTeamId = 990000 + index, home = "Alpha $index", awayTeamId = 990099, away = "Zulu") }
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            TeamSelectionScreen(SportsViewModel.State(remoteMatches = fixtures), {}, {})
        } }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("team-990000").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("team-scroll-initial").assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        val list = rule.onNode(hasScrollToIndexAction())
        list.performTouchInput { down(center); advanceEventTime(300); moveTo(center.copy(y = center.y - 180f)) }
        rule.mainClock.advanceTimeBy(120)
        rule.onNodeWithTag("team-scroll-initial").assertIsDisplayed()
        rule.onNode(hasText("A") and hasAnyAncestor(hasTestTag("team-scroll-initial"))).assertIsDisplayed()
        list.performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitUntil(10000) { rule.onAllNodesWithTag("team-scroll-initial").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun searchClearEmptyStateAndSelectionKeepThePageMinimal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { localBackend(context).prepare() }
        val fixture = sampleMatches.first().copy(homeTeamId = 1000360, home = "Manchester United", awayTeamId = 1000359, away = "Arsenal")
        val second = fixture.copy(id = "other", homeTeamId = 1000331, home = "Brighton & Hove Albion")
        var chosen: FootballTeam? = null
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            TeamSelectionScreen(SportsViewModel.State(remoteMatches = listOf(fixture, second)), {}, { chosen = it })
        } }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("team-1000359").fetchSemanticsNodes().isNotEmpty() }
        for (initial in listOf("A", "B", "M")) rule.onNodeWithTag("team-initial-$initial").assertDoesNotExist()
        rule.onNodeWithTag("team-scroll-initial").assertDoesNotExist()
        rule.onNodeWithText("选择你的主队").assertDoesNotExist()
        rule.onAllNodesWithText("赛季", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("选择后", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("team-search").performTextInput("曼")
        rule.onNodeWithTag("team-1000360").assertExists()
        rule.onNodeWithTag("team-1000359").assertDoesNotExist()
        rule.onNodeWithTag("team-search-clear").performClick()
        rule.onNodeWithTag("team-search").performTextInput("arsenal")
        rule.onNodeWithTag("team-1000359").assertExists()
        rule.onNodeWithTag("team-1000360").assertDoesNotExist()
        rule.onNodeWithTag("team-search").performTextReplacement("no such club")
        rule.onNodeWithText("未找到球队").assertExists()
        rule.onNodeWithTag("team-search-clear").performClick()
        rule.onNodeWithTag("team-1000359").performClick()
        rule.runOnIdle { assertEquals(1000359, chosen?.id); assertEquals("Arsenal", chosen?.name) }
    }
}
