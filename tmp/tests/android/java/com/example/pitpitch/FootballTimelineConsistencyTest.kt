package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import com.example.pitpitch.feature.MatchDetail
import com.example.pitpitch.ui.SportsColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset

class FootballTimelineConsistencyTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun body() = instrumentation.context.assets.open("espn-german-events.json").bufferedReader().use { it.readText() }
    private fun match() = sampleMatches.first().copy(id = "fb-espn-ger.1-401884811", competitionId = "ger.1",
        competition = "德甲", home = "1. FC Union Berlin", homeShort = "Union Berlin", homeTeamId = 1000598,
        away = "Eintracht Frankfurt", awayShort = "Frankfurt", awayTeamId = 1000125,
        homeBadgeUrl = null, awayBadgeUrl = null, score = "3 : 3", status = "完场", isDemo = false)

    @Test fun providerAliasesPreserveExplicitScoresAcrossDatabaseRoundTrip() = runBlocking {
        val match = match()
        val detail = EspnMatchAdapter.detail(body(), match)
        val goal = detail.events.first { it.type.startsWith("goal") }
        assertEquals("1-0", footballEventScore(goal, match))
        assertTrue(goal.text.contains("Union Berlin 1, Eintracht Frankfurt 0"))
        assertEquals("1-2", footballEventScore(detail.events.first { it.type == "halftime" }, match))
        assertEquals("3-3", footballEventScore(detail.events.first { it.type == "end-regular-time" }, match))
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, PitPitchDatabase::class.java).build()
        try {
            val store = FootballStore(db)
            store.writeDetail(match.id, detail, "2026-10-21T00:00:00Z")
            assertEquals(detail, store.detail(match))
            // An installed older APK already stored these events without the alias-derived score.
            val url = "${EspnFootballAdapter.SITE}ger.1/summary?event=401884811"
            val raw = body()
            db.localData().putSnapshots(listOf(SourceSnapshotRow(url, "network", raw, "2026-10-21T00:00:00Z",
                LocalDataImporter.sha(raw.toByteArray()))))
            store.writeDetail(match.id, detail.copy(events = detail.events.map { it.copy(sourceScore = null) }), "2026-10-21T00:00:00Z")
            val client = JsonClient(database = db, transport = { error("Cached recovery must not use HTTP") })
            val recovered = EuropeanFootballRepository(client, store).cachedMatchDetail(match)!!
            assertEquals(detail, recovered.data)
            assertEquals(detail, store.detail(match))
            assertEquals("2026-10-21T00:00:00Z", recovered.fetchedUtc)
        } finally { db.close() }
    }

    @Test fun allSevenCompetitionsUseIdenticalAxisCardsClocksAndStageNodes() {
        val original = match()
        val detail = EspnMatchAdapter.detail(body(), original)
        val selected = mutableStateOf(footballCompetitions.first())
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            MatchDetail(original.copy(id = "test-${selected.value.id}", competitionId = selected.value.id,
                competition = selected.value.title), ZoneOffset.UTC, null, {}, detail = RemoteSnapshot(detail, "2026-10-21T00:00:00Z"))
        } }
        val goals = listOf(detail.events.first { it.teamId == original.homeTeamId && it.type.startsWith("goal") },
            detail.events.first { it.teamId == original.awayTeamId && it.type.startsWith("goal") })
        val geometry = mutableMapOf<String, List<Float>>()
        for (competition in footballCompetitions) {
            rule.runOnIdle { selected.value = competition }
            for (event in goals) {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("detail-event-${event.id}"))
                val clock = rule.onNodeWithTag("event-clock-${event.id}").fetchSemanticsNode().boundsInRoot
                val card = rule.onNodeWithTag("event-content-${event.id}").fetchSemanticsNode().boundsInRoot
                val axis = rule.onNodeWithTag("event-axis-${event.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals(axis.center.x, clock.center.x, 1f)
                if (event.teamId == original.homeTeamId) assertTrue(card.right <= clock.left)
                else assertTrue(card.left >= clock.right)
                val columns = listOf(axis.width, clock.left, clock.width, card.left, card.width, card.height)
                geometry[event.id]?.zip(columns)?.forEach { (before, after) -> assertEquals(before, after, 1f) }
                    ?: run { geometry[event.id] = columns }
                rule.onNodeWithTag("event-member-${event.id}").assertTextContains(event.players.first(), substring = true)
            }
            val halftime = detail.events.first { it.type == "halftime" }
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("detail-event-${halftime.id}"))
            rule.onNodeWithTag("event-node-${halftime.id}").assertExists()
            rule.onNodeWithText("上半场结束 1-2").assertExists()
            val screenshot = java.io.File(instrumentation.targetContext.cacheDir,
                "pitpitch-validation/timeline-${competition.id}.png")
            screenshot.parentFile!!.mkdirs()
            screenshot.outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            for (event in detail.events.filter { it.type in listOf("yellow-card", "substitution") })
                rule.onNodeWithTag("detail-event-${event.id}").assertDoesNotExist()
        }
    }
}
