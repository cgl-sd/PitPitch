package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.MatchDetail
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

class MatchDetailPresentationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun loadingShowsContentImmediatelyAndDoesNotMoveMatchHeader() {
        val match = archivedMatch("704467", 2025)
        val body = InstrumentationRegistry.getInstrumentation().context.assets.open("espn-match-detail.json").bufferedReader().use { it.readText() }
        val response = EspnMatchAdapter.detail(body, match)
        val loading = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(match, ZoneId.systemDefault(), null, {}, loading = loading.value,
                detail = if (loading.value) null else RemoteSnapshot(response, "2025-01-02T00:00:00Z"))
        } }
        rule.onNodeWithText("正在读取比赛详情").assertDoesNotExist()
        rule.onNodeWithText("事件读取中").assertDoesNotExist()
        rule.onNodeWithText("暂无技术统计").assertDoesNotExist()
        rule.onNodeWithTag("match-placeholder-statistics").assertExists()
        rule.onNodeWithTag("match-placeholder-events").assertExists()
        val before = rule.onNodeWithTag("match-${match.id}").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { loading.value = false }
        rule.onNodeWithTag("match-statistics").assertExists()
        val after = rule.onNodeWithTag("match-${match.id}").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        assertEquals(before.bottom, after.bottom, 1f)
    }
    @Test fun whiteTeamBarsUseFixedColorAndHaveNoBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val match = archivedMatch("704467", 2025).copy(awayTeamId = 1000367, away = "Tottenham Hotspur", awayBadgeUrl = null)
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            com.example.pitpitch.feature.MatchStatistics(match, listOf(MatchStatistic("射门", "12", "12"), MatchStatistic("点球进球", "0", "0")))
        } }
        rule.waitForIdle()
        val inset = (context.resources.displayMetrics.density * 12.5f).toInt()
        val row = rule.onNodeWithTag("statistic-射门").captureToImage().asAndroidBitmap()
        val right = row.getPixel((row.width * .75f).toInt(), row.height - inset)
        assertEquals(android.graphics.Color.rgb(22, 133, 216), right)
        assertEquals(right, row.getPixel((row.width * .25f).toInt(), row.height - inset))
        // Validate no gray track at all in the zero-value row.
        val zero = rule.onNodeWithTag("statistic-点球进球").captureToImage().asAndroidBitmap()
        for (x in 0 until zero.width) {
            val pixel = zero.getPixel(x, zero.height - inset)
            assertEquals(android.graphics.Color.WHITE, pixel)
        }
        capture("statistics-white-team-no-track")
    }
    @Test fun clockAndBreakStatesStayBelowScore() {
        val match = archivedMatch("704467", 2025)
        val state = androidx.compose.runtime.mutableStateOf(match.copy(status = "进行中", liveDetail = "67:23"))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            com.example.pitpitch.feature.MatchCard(state.value, ZoneId.of("Asia/Shanghai"))
        } }
        rule.onNodeWithTag("match-score-status").assertTextEquals("67:23")
        val score = rule.onNodeWithText(match.score).fetchSemanticsNode().boundsInRoot
        val status = rule.onNodeWithTag("match-score-status").fetchSemanticsNode().boundsInRoot
        assertTrue(status.top >= score.bottom)
        rule.onNodeWithText("进行中").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(liveDetail = "HT") }
        rule.onNodeWithTag("match-score-status").assertTextEquals("中场")
        rule.runOnIdle { state.value = state.value.copy(status = "完场") }
        rule.onNodeWithTag("match-score-status").assertTextEquals("完场")
    }
    @Test fun realSummaryDisplaysEventsAndPublishedFormationWithoutOverview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val body = instrumentation.context.assets.open("espn-match-detail.json").bufferedReader().use { it.readText() }
        val match = archivedMatch("704467", 2025).copy(eventsAvailable = false, statsAvailable = false)
        val detail = EspnMatchAdapter.detail(body, match)
        assertEquals(4, detail.events.count { it.type.startsWith("goal") })
        assertEquals(3, detail.events.count { it.type == "yellow-card" })
        assertNull(publishedLineupRows(detail.lineups[0])) // Provider positions do not reconcile with 4-3-1-2.
        val rows = lineupRows(detail.lineups[1])!!
        assertEquals(listOf(1, 4, 3, 3), rows.map { it.size })
        assertEquals(listOf("Riccardo Calafiori", "Gabriel Magalhães", "William Saliba", "Jurriën Timber"), rows[1].map { it.name })
        assertEquals(detail.lineups[1].starters.map { it.id }.toSet(), rows.flatten().map { it.id }.toSet())
        assertNull(publishedLineupRows(detail.lineups[1].copy(formation = null)))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(match, ZoneId.of("Asia/Shanghai"), null, {}, detail = RemoteSnapshot(detail, "2025-01-02T00:00:00Z"))
        } }
        rule.onNodeWithText("概况").assertDoesNotExist()
        rule.onNodeWithText("数据与事件").assertExists()
        rule.onNodeWithTag("match-score-status").assertTextEquals("完场")
        rule.onNodeWithTag("match-statistics").assertExists()
        for (statistic in compactMatchStatistics(detail.statistics)) {
            rule.onNodeWithTag("statistic-${statistic.label}").assertExists()
        }
        for (label in listOf("射门转化率", "越位", "扑救")) rule.onNodeWithText(label).assertDoesNotExist()
        capture("match-compact-statistics")
        for (event in compactFootballEvents(detail.events).filter { it.teamId != null }.groupBy { it.teamId }.values.map { it.first() }) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("detail-event-${event.id}"))
            val symbol = rule.onNodeWithTag("event-clock-${event.id}").fetchSemanticsNode().boundsInRoot
            val content = rule.onNodeWithTag("event-content-${event.id}").fetchSemanticsNode().boundsInRoot
            if (event.teamId == match.homeTeamId) assertTrue(content.right <= symbol.left)
            else assertTrue(content.left >= symbol.right)
            rule.onNodeWithText(event.clock).assertExists()
            event.players.firstOrNull()?.let { rule.onNodeWithTag("event-member-${event.id}").assertTextContains(it, substring = true) }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithTag("event-member-${event.id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().hasVisualOverflow)
        }
        capture("match-real-events")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("首发").performClick()
        rule.onNode(hasText(FootballNames.get(instrumentation.targetContext).team(match.awayTeamId, match.awayShort)) and hasClickAction()).performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("lineup-pitch"))
        rule.onNodeWithTag("lineup-pitch").assertExists()
        rule.onNodeWithText("首发 · 11 人").assertDoesNotExist()
        rule.onNodeWithText("阵型 4-3-3").assertExists()
        rule.waitUntil(10000) {
            val bitmap = rule.onNodeWithTag("pitch-shirt-${rows.last().first().id}").captureToImage().asAndroidBitmap()
            val color = bitmap.getPixel((bitmap.width * .35f).toInt(), (bitmap.height * .8f).toInt())
            android.graphics.Color.red(color) > android.graphics.Color.green(color) * 2
        }
        val pitch = rule.onNodeWithTag("lineup-pitch").fetchSemanticsNode().boundsInRoot
        rows.flatten().forEach {
            rule.onNodeWithTag("pitch-player-${it.id}").assertExists()
            rule.onNodeWithText(it.name).assertExists()
            val shirt = rule.onNodeWithTag("pitch-shirt-${it.id}").fetchSemanticsNode().boundsInRoot
            assertTrue(shirt.left >= pitch.left && shirt.right <= pitch.right)
        }
        val keeper = rule.onNodeWithTag("pitch-shirt-${rows.first().single().id}").fetchSemanticsNode().boundsInRoot
        val striker = rule.onNodeWithTag("pitch-shirt-${rows.last().first().id}").fetchSemanticsNode().boundsInRoot
        assertTrue("Attack is upward; goalkeeper stays below the forwards", keeper.top > striker.bottom)
        capture("match-real-lineup")
    }
    @Test fun compactTimelineOmitsCardsAndSubstitutionsWithoutChangingStoredRecords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val body = instrumentation.context.assets.open("espn-match-detail.json").bufferedReader().use { it.readText() }
        val match = archivedMatch("704467", 2025)
        val detail = EspnMatchAdapter.detail(body, match)
        val original = detail.events.toList()
        val groups = footballTimelineGroups(original)
        assertEquals(original, groups.flatMap { it.events })
        assertEquals(listOf(4, 3), groups.filter { it.events.size > 1 }.map { it.events.size })
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(match, ZoneId.of("Asia/Shanghai"), null, {}, detail = RemoteSnapshot(detail, "2025-01-02T00:00:00Z"))
        } }
        for (event in compactFootballEvents(original)) {
            val tag = "detail-event-${event.id}"
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).assertExists()
            for (hidden in original.filter { it.type == "yellow-card" || it.type == "substitution" })
                rule.onNodeWithTag("detail-event-${hidden.id}").assertDoesNotExist()
        }
        capture("match-compact-timeline")
        assertEquals(original, detail.events)
    }
    @Test fun brentfordSourceSecondHalfNodeSurvivesParsingAndDisplay() {
        assertSourceSecondHalf("espn-match-detail.json", "704467", 2025, "44476324",
            listOf("44476163", "44476201", "44476250", "44476320", "44476324", "44476336", "44476346", "44476494"))
    }
    @Test fun bournemouthSourceSecondHalfNodeSurvivesParsingAndDisplay() {
        assertSourceSecondHalf("espn-red-card-detail.json", "704351", 2024, "44010981",
            listOf("44010192", "44010590", "44010862", "44010981", "44011314", "44011458", "44011704"))
    }
    private fun assertSourceSecondHalf(asset: String, eventId: String, year: Int, nodeId: String, expectedIds: List<String>) {
        val body = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader().use { it.readText() }
        val sourceEvents = org.json.JSONObject(body).getJSONArray("keyEvents")
        val source = (0 until sourceEvents.length()).map { sourceEvents.getJSONObject(it) }.single { it.getString("id") == nodeId }
        val match = archivedMatch(eventId, year)
        val detail = EspnMatchAdapter.detail(body, match)
        val original = detail.events.toList()
        val node = original.single { it.id == nodeId }
        assertEquals("start-2nd-half", node.type)
        assertEquals(source.getJSONObject("type").getString("type"), node.type)
        assertEquals(source.getJSONObject("clock").getString("displayValue"), node.clock)
        assertEquals(source.getJSONObject("clock").getDouble("value"), node.seconds!!, 0.0)
        assertEquals(source.getJSONObject("period").getInt("number"), node.period)
        assertEquals(source.optString("text").ifBlank { source.getJSONObject("type").getString("text") }, node.text)
        val visible = compactFootballEvents(original)
        assertEquals(expectedIds, visible.map { it.id })
        visible.forEach { assertSame(original.single { sourceEvent -> sourceEvent.id == it.id }, it) }
        assertTrue(original.any { it.type == "yellow-card" })
        assertTrue(original.any { it.type == "substitution" })
        assertFalse(visible.any { it.type == "yellow-card" || it.type == "substitution" })
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(match, ZoneId.of("Asia/Shanghai"), null, {}, detail = RemoteSnapshot(detail, "2026-10-12T00:00:00Z"))
        } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("detail-event-$nodeId"))
        rule.onNodeWithTag("detail-event-$nodeId").assertIsDisplayed()
        for (hidden in original.filter { it.type == "yellow-card" || it.type == "substitution" })
            rule.onNodeWithTag("detail-event-${hidden.id}").assertDoesNotExist()
        assertEquals(original, detail.events)
    }
    @Test fun actualRedCardAndScoredPenaltyHaveSeparateLabels() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val body = context.assets.open("espn-red-card-detail.json").bufferedReader().use { it.readText() }
        val match = archivedMatch("704351", 2024)
        val detail = EspnMatchAdapter.detail(body, match)
        val red = detail.events.single { it.type == "red-card" }
        assertTrue(red.players.any { it.contains("Saliba") })
        assertEquals(1000359, red.teamId)
        val penalty = detail.events.single { it.type == "penalty---scored" }
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(match, ZoneId.of("Asia/Shanghai"), null, {}, detail = RemoteSnapshot(detail, "2026-10-11T00:00:00Z"))
        } }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("detail-event-${red.id}"))
        rule.onNode(hasText("红牌", substring = true) and hasAnyAncestor(hasTestTag("detail-event-${red.id}"))).assertExists()
        capture("match-real-red-card")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("detail-event-${penalty.id}"))
        rule.onNode(hasText("点球进球", substring = true) and hasAnyAncestor(hasTestTag("detail-event-${penalty.id}"))).assertExists()
        val invalid = org.json.JSONObject(body)
        invalid.getJSONArray("keyEvents").getJSONObject(1).put("team", org.json.JSONObject().put("id", "99999"))
        try { EspnMatchAdapter.detail(invalid.toString(), match); fail("Foreign event team accepted") }
        catch (_: IllegalArgumentException) {}
    }
    private fun archivedMatch(eventId: String, calendarYear: Int): FootballMatch {
        val archive = SportsArchive(InstrumentationRegistry.getInstrumentation().targetContext)
        val league = footballCompetitions.first { it.id == "eng.1" }
        val match = archive.read("${EspnFootballAdapter.SITE}eng.1/scoreboard?dates=$calendarYear&limit=1000") {
            EspnFootballAdapter.matches(it, league, 2024)
        }!!.data.first { it.id.endsWith("-$eventId") }
        return applyFootballRound(match, 2024, archive.footballRounds(2024)[match.id])
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "pitpitch-validation/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
