package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.example.pitpitch.ui.Line
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.feature.MatchDetail
import com.example.pitpitch.ui.SportsColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import java.io.File

class LineupFormationCompatibilityTest {
    @get:Rule val rule = createComposeRule()
    @Test fun publishedUnitedLeedsLineupsSurviveStorageAndDisplayBothPitches() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val raw = instrumentation.context.assets.open("espn-united-leeds-lineups.json").bufferedReader().use { it.readText() }
        val match = sampleMatches.first().copy(id = "fb-espn-eng.1-740912", competitionId = "eng.1",
            homeTeamId = 1000360, awayTeamId = 1000357, home = "Manchester United", away = "Leeds United",
            homeShort = "Man United", awayShort = "Leeds", status = "完场", isDemo = false)
        val parsed = EspnMatchAdapter.detail(raw, match)
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, PitPitchDatabase::class.java).build()
        val detail = try {
            val store = FootballStore(db)
            store.writeDetail(match.id, parsed, "2026-10-21T00:00:00Z")
            store.detail(match)!!.also { assertEquals(parsed.lineups.associateBy { lineup -> lineup.teamId }, it.lineups.associateBy { lineup -> lineup.teamId }) }
        } finally { db.close() }
        val united = detail.lineups.single { it.teamId == match.homeTeamId }
        val leeds = detail.lineups.single { it.teamId == match.awayTeamId }
        assertEquals(listOf(1, 4, 2, 3, 1), lineupRows(united)!!.map { it.size })
        assertEquals(listOf(1, 3, 4, 2, 1), lineupRows(leeds)!!.map { it.size })
        assertEquals(listOf("Noah Okafor", "Brenden Aaronson"), lineupRows(leeds)!![3].map { it.name })
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(match, ZoneOffset.UTC, null, {}, detail = RemoteSnapshot(detail, "2026-10-21T00:00:00Z"))
        } }
        rule.onNodeWithText("首发").performClick()
        rule.onNodeWithTag("match-detail-section-1").assertIsSelected()
        assertCapsule("match-lineup-side")
        val controlScreenshot = File(instrumentation.targetContext.cacheDir, "pitpitch-validation/lineup-capsules.png")
        controlScreenshot.parentFile!!.mkdirs()
        controlScreenshot.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        for (lineup in listOf(united, leeds)) {
            val names = FootballNames.get(instrumentation.targetContext)
            val name = if (lineup.teamId == match.homeTeamId) names.team(match.homeTeamId, match.homeShort) else names.team(match.awayTeamId, match.awayShort)
            rule.onNode(hasText(name) and hasClickAction()).performClick()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("lineup-pitch"))
            rule.onNodeWithTag("lineup-pitch").assertExists()
            lineup.starters.forEach { rule.onNodeWithTag("pitch-player-${it.id}").assertExists() }
            val screenshot = File(instrumentation.targetContext.cacheDir, "pitpitch-validation/lineup-${lineup.teamId}.png")
            screenshot.parentFile!!.mkdirs()
            screenshot.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun unavailablePositionsKeepPitchAndExplicitSchematicLabel() {
        val match = sampleMatches.first().copy(isDemo = false, homeTeamId = 1000360, awayTeamId = 1000357,
            homeShort = "Man United", awayShort = "Leeds")
        val starters = (1..11).map { FootballPlayer("schematic-$it", "Starter $it", "$it", "位置未提供", null, null, null) }
        val lineup = MatchLineup(requireNotNull(match.homeTeamId), "4-2-3-1", starters, emptyList(), emptyList())
        val selected = androidx.compose.runtime.mutableStateOf(lineup)
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(match, ZoneOffset.UTC, null, {}, detail = RemoteSnapshot(FootballDetail(listOf(selected.value), emptyList()), "2026-10-21T00:00:00Z"))
        } }
        rule.onNodeWithText("首发").performClick()
        rule.onNodeWithText("阵型 4-2-3-1 · 站位示意").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("lineup-pitch"))
        starters.forEach { rule.onNodeWithTag("pitch-player-${it.id}").assertExists() }
        rule.runOnIdle { selected.value = lineup.copy(formation = null, starters = starters.take(7)) }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("站位示意").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("lineup-pitch"))
        starters.take(7).forEach { rule.onNodeWithTag("pitch-player-${it.id}").assertExists() }
        starters.drop(7).forEach { rule.onNodeWithTag("pitch-player-${it.id}").assertDoesNotExist() }
    }

    private fun assertCapsule(tag: String) {
        val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals("Continuous capsule outline: $tag", Line, pixels[pixels.width / 2, 1])
    }

}
