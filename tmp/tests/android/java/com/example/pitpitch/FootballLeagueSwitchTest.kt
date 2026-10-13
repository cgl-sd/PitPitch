package com.example.pitpitch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.FootballScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FootballLeagueSwitchTest {
    @get:Rule val rule = createComposeRule()
    private val zone = ZoneId.systemDefault()
    private val now = Instant.now()
    private val leagues = listOf("eng.1", "esp.1", "fra.1")
    private val pastCounts = listOf(20, 44, 9)
    private val fixtures = leagues.flatMapIndexed { i, id -> matches(id, pastCounts[i]) }
    private fun initialState() = SportsViewModel.State(ready = true, matches = fixtures)
    private val fixtureMatcher = SemanticsMatcher("fixture row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("match-switch-") == true
    }

    @Test fun selectedLeagueAndFixturesStayTogetherUntilOwnerCommitsSelection() {
        val state = mutableStateOf(initialState())
        var requested: String? = null
        rule.setContent { FootballScreen(state.value, zone, {}, competition = { requested = it }) }
        assertNextFrame("eng.1")
        rule.mainClock.autoAdvance = false
        try {
            select("esp.1")
            advanceFrame()
            assertNextFrame("eng.1")
            rule.runOnUiThread { state.value = state.value.copy(footballCompetition = requireNotNull(requested)) }
            advanceFrame()
            assertNextFrame("esp.1")
        } finally { rule.mainClock.autoAdvance = true }
    }

    @Test fun rapidLeagueChangesRenderTheirNextFixtureInTheFirstFrame() {
        val state = mutableStateOf(initialState())
        rule.setContent { FootballScreen(state.value, zone, {}, competition = {
            state.value = state.value.copy(footballCompetition = it)
        }) }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        rule.mainClock.autoAdvance = false
        try {
            for (id in listOf("esp.1", "fra.1", "eng.1", "esp.1", "eng.1", "fra.1")) {
                select(id)
                advanceFrame()
                assertNextFrame(id)
                val top = rule.onNodeWithTag(nextTag(id)).fetchSemanticsNode().boundsInRoot.top
                advanceFrame()
                assertNextFrame(id)
                assertEquals("No second-frame relocation for $id", top,
                    rule.onNodeWithTag(nextTag(id)).fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        } finally { rule.mainClock.autoAdvance = true }
    }

    @Test fun firstFrameAfterDelayedDataArrivesStartsAtNextFixture() {
        val state = mutableStateOf(initialState().copy(matches = fixtures.filter { it.competitionId == "eng.1" }))
        rule.setContent { FootballScreen(state.value, zone, {}, competition = {
            state.value = state.value.copy(footballCompetition = it, footballLoading = true)
        }) }
        assertNextFrame("eng.1")
        rule.mainClock.autoAdvance = false
        try {
            select("esp.1")
            advanceFrame()
            rule.onAllNodes(fixtureMatcher).assertCountEquals(0)
            rule.runOnUiThread { state.value = state.value.copy(matches = fixtures, footballLoading = false) }
            advanceFrame()
            assertNextFrame("esp.1")
        } finally { rule.mainClock.autoAdvance = true }
    }

    @Test fun backgroundRefreshKeepsManualPositionWithinSameLeague() {
        val state = mutableStateOf(initialState())
        rule.setContent { FootballScreen(state.value, zone, {}) }
        val scrolled = fixtures.filter { it.competitionId == "eng.1" }.sortedBy { homeMatchTime(it, zone) }[6]
        val tag = "match-${scrolled.id}"
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        val top = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val round = rule.onNodeWithTag("football-round").fetchSemanticsNode().config[SemanticsProperties.Text]
        rule.runOnUiThread { state.value = state.value.copy(footballLoading = true,
            matches = state.value.matches.map { if (it.id == scrolled.id) it.copy(score = "2 : 0") else it }) }
        rule.waitForIdle()
        rule.runOnUiThread { state.value = state.value.copy(footballLoading = false) }
        rule.waitForIdle()
        assertEquals(top, rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(round, rule.onNodeWithTag("football-round").fetchSemanticsNode().config[SemanticsProperties.Text])
    }

    private fun select(id: String) {
        val title = footballCompetitions.first { it.id == id }.title
        rule.onNode(hasText(title) and hasClickAction()).performClick()
    }
    private fun advanceFrame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }
    private fun nextTag(id: String) = "match-switch-$id-next-0"
    private fun assertNextFrame(id: String) {
        val title = footballCompetitions.first { it.id == id }.title
        rule.onNode(hasText(title) and hasClickAction()).assertIsSelected()
        val viewport = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        val visible = rule.onAllNodes(fixtureMatcher).fetchSemanticsNodes().filter {
            it.positionInRoot.y >= viewport.top && it.positionInRoot.y + it.size.height <= viewport.bottom && it.size.width > 0
        }.sortedBy { it.positionInRoot.y }
        assertTrue("Expected visible fixtures for $id", visible.isNotEmpty())
        assertEquals("First complete fixture must already be the new league's next match", nextTag(id),
            visible.first().config[SemanticsProperties.TestTag])
        assertTrue("No fixtures from a different league in the same frame", visible.all {
            it.config[SemanticsProperties.TestTag].startsWith("match-switch-$id-")
        })
        rule.onNodeWithTag("football-round").assertTextEquals("第 ${pastCounts[leagues.indexOf(id)] + 1} 轮")
    }
    private fun matches(id: String, past: Int): List<FootballMatch> {
        val title = footballCompetitions.first { it.id == id }.title
        return (0 until past + 20).map { i ->
            val future = i >= past
            val kickoff = if (future) now.plusSeconds((i - past + 1) * 86400L) else now.minusSeconds((past - i) * 86400L)
            FootballMatch(id = "switch-$id-${if (future) "next-${i - past}" else "past-$i"}",
                competition = title, competitionId = id, stage = "第 ${i + 1} 轮", roundNumber = i + 1,
                home = "$title Home $i", away = "$title Away $i", homeShort = "Home", awayShort = "Away",
                score = if (future) "— : —" else "1 : 0", status = if (future) "待赛" else "完场",
                stadium = "Test ground", date = kickoff.toString().take(10), kickoffUtc = kickoff.toString(), isDemo = false)
        }
    }
}
