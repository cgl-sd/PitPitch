package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.localBackend
import com.example.pitpitch.feature.FootballScreen
import com.example.pitpitch.feature.PlayerRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

class FootballPlayerDisplayTest {
    @get:Rule val rule = createComposeRule()
    @Test fun providerFullNamesRemainUnchangedWithoutPhotosOrPackagedTranslations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { localBackend(context).prepare() }
        assertFalse(context.assets.list("")!!.contains("football-player-names.json"))
        assertNull(com.example.pitpitch.data.db.PitPitchDatabase.build(context).localData().documentLength("football-player-names.json"))
        val player = FootballPlayer("253989", "Erling Haaland", "9", "前锋", "10", "8", "1", "https://a.espncdn.com/i/headshots/soccer/players/full/253989.png")
        val shown = mutableStateOf(player)
        rule.setContent { MaterialTheme { PlayerRow(shown.value) } }
        for (name in listOf("Erling Haaland", "Bruno Fernandes", "Martin Ødegaard")) {
            rule.runOnIdle { shown.value = player.copy(name = name) }
            rule.onNodeWithText(name).assertExists()
            rule.onAllNodes(hasContentDescription("头像", substring = true)).assertCountEquals(0)
        }
        rule.onNodeWithText("厄德高").assertDoesNotExist()
        assertEquals("Erling Haaland", player.name)
        assertNotNull(player.photoUrl)
        val out = File(context.cacheDir, "pitpitch-validation/player-original-full-name.png"); out.parentFile!!.mkdirs()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun goalsAndAssistsRankOriginalFullNamesByTheirDisplayedStatistic() {
        val entries = listOf(
            LeaderEntry("Bruno Fernandes", "Manchester United", 11, 14, 30),
            LeaderEntry("Martin Ødegaard", "Arsenal", 17, 8, 28),
            LeaderEntry("Erling Haaland", "Manchester City", 29, 6, 31),
            LeaderEntry("Unavailable Player", "Chelsea", null, null, null))
        val state = SportsViewModel.State(ready = true, footballSeason = 2026,
            leaderboards = mapOf("2026|eng.1" to RemoteSnapshot(entries, "2026-10-12T00:00:00Z")))
        val shown = mutableStateOf(state)
        rule.setContent { MaterialTheme { FootballScreen(shown.value, ZoneId.systemDefault(), {}) } }
        rule.onNodeWithTag("football-section-2").performClick()
        assertRankedPlayers(listOf("Erling Haaland" to "29", "Martin Ødegaard" to "17", "Bruno Fernandes" to "11", "Unavailable Player" to "—"))
        rule.onNodeWithText("助攻榜").performClick()
        assertRankedPlayers(listOf("Bruno Fernandes" to "14", "Martin Ødegaard" to "8", "Erling Haaland" to "6", "Unavailable Player" to "—"))
        rule.onNodeWithText("进球榜").performClick()
        assertRankedPlayers(listOf("Erling Haaland" to "29", "Martin Ødegaard" to "17", "Bruno Fernandes" to "11", "Unavailable Player" to "—"))
        val refreshed = entries.map { if (it.name == "Bruno Fernandes") it.copy(goals = 31) else it }
        rule.runOnIdle { shown.value = shown.value.copy(leaderboards = mapOf("2026|eng.1" to RemoteSnapshot(refreshed, "2026-10-12T01:00:00Z"))) }
        assertRankedPlayers(listOf("Bruno Fernandes" to "31", "Erling Haaland" to "29", "Martin Ødegaard" to "17", "Unavailable Player" to "—"))
        rule.runOnIdle { shown.value = shown.value.copy(footballLoading = true) }
        assertRankedPlayers(listOf("Bruno Fernandes" to "31", "Erling Haaland" to "29", "Martin Ødegaard" to "17", "Unavailable Player" to "—"))
        rule.onNodeWithText("布鲁诺").assertDoesNotExist()
    }
    private fun assertRankedPlayers(expected: List<Pair<String, String>>) {
        var previousBottom = Float.NEGATIVE_INFINITY
        expected.forEachIndexed { index, (name, value) ->
            val tag = "leaderboard-row-${index + 1}"
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
            val row = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$name must follow the preceding rank", row.top >= previousBottom)
            previousBottom = row.bottom
            val inRow = hasAnyAncestor(hasTestTag(tag))
            rule.onNode(hasText(name) and inRow).assertIsDisplayed()
            rule.onNode(hasText(value) and inRow).assertIsDisplayed()
            rule.onNode(hasText("${index + 1}") and inRow).assertIsDisplayed()
        }
    }
    @Test fun oversizedUnicodeDocumentsRoundTripThroughBoundedDatabaseReads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val body = "字😀".repeat(350000)
        assertTrue(body.toByteArray().size > 2 * 1024 * 1024)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.pitpitch.data.db.PitPitchDatabase::class.java).build()
        try {
            val dao = db.localData()
            dao.putDocuments(listOf(com.example.pitpitch.data.db.LocalDocumentRow("fixture", body, "fixture")))
            assertEquals(body, dao.documentBody("fixture"))
            assertNull(dao.documentBody("missing"))
            dao.putDocuments(listOf(com.example.pitpitch.data.db.LocalDocumentRow("fixture", "", "fixture")))
            assertEquals("", dao.documentBody("fixture"))
        } finally { db.close() }
    }
}
