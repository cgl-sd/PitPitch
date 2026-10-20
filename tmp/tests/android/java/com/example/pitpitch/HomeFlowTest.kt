package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.data.local.UserStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun realHomeHasOneOfficialRoundPerCompetitionAndNoWeekOverride() {
        val store = UserStore(rule.activity)
        val before = runBlocking { store.settings.first() }
        val competitions = footballCompetitions.map { it.id } + "f1"
        try {
            runBlocking { competitions.forEach { store.homeCompetition(it, true) } }
            rule.waitUntil(40000) { rule.onAllNodes(prefix("home-round-")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("赛事赛程").assertExists()
            rule.onNodeWithTag("home-week").assertDoesNotExist()
            rule.onNodeWithText("本周赛事").assertDoesNotExist()
            rule.onNodeWithText("本周").assertDoesNotExist()
            rule.onNodeWithText("下周").assertDoesNotExist()
            rule.onNodeWithText(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM.dd")) + " · 北京时间").assertDoesNotExist()
            for (competition in footballCompetitions) {
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(prefix("home-round-${competition.title}|"))
                rule.onAllNodes(prefix("home-round-${competition.title}|")).assertCountEquals(1)
            }
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(prefix("home-round-欧冠|"))
            // The card is clickable, so its semantics merge: the round text lives on the card node, not a separate child.
            rule.onNode(prefix("home-round-欧冠|") and hasText("第 ", substring = true)).assertExists()
            rule.onNodeWithText("下一比赛周").assertDoesNotExist()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("f1-weekend"))
            rule.onNodeWithTag("f1-weekend-detail").assertDoesNotExist()
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
            val file = File(rule.activity.cacheDir, "pitpitch-validation/home-official-rounds.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            rule.onNodeWithTag("open-settings").performClick()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("open-about"))
            rule.onNodeWithTag("open-about").assertExists()
            assertEquals(before.homeTeam, runBlocking { store.settings.first().homeTeam })
        } finally { runBlocking { competitions.forEach { store.homeCompetition(it, it !in before.hiddenHomeCompetitions) } } }
    }
    private fun prefix(value: String) = SemanticsMatcher("tag starts with $value") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(value) == true
    }
}
