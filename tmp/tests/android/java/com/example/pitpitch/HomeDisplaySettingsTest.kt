package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.data.local.UserStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeDisplaySettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun back() { rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
    private fun choice(id: String) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("home-visible-$id"))
        rule.onNodeWithTag("home-visible-$id").performClick()
    }
    private fun capture(name: String) {
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        val file = File(rule.activity.cacheDir, "pitpitch-validation/home-display-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun onlyHomeHasSettingsAndSelectionPersistsWithoutChangingOtherPages() {
        val store = UserStore(rule.activity)
        val before = runBlocking { store.settings.first() }
        val ids = footballCompetitions.map { it.id } + "f1"
        try {
            runBlocking { ids.forEach { store.homeCompetition(it, true) } }
            rule.waitUntil(10000) { rule.onAllNodesWithTag("open-settings").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("open-settings").performClick()
            capture("settings")
            rule.onNodeWithTag("utc-toggle").assertDoesNotExist()
            rule.onNodeWithText("时间设置").assertDoesNotExist()
            rule.onNodeWithTag("settings-data-sources").assertDoesNotExist()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("open-about"))
            rule.onNodeWithTag("open-about").performClick()
            rule.onNodeWithTag("app-version").assertDoesNotExist()
            rule.onNodeWithTag("settings-data-sources").assertExists()
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10000) { rule.onAllNodesWithTag("settings-data-sources").fetchSemanticsNodes().isNotEmpty() }
            capture("sources")
            back()
            rule.onNodeWithTag("app-version").assertDoesNotExist()
            rule.onNodeWithTag("open-about").assertExists()
            ids.forEach(::choice)
            rule.waitUntil(10000) { runBlocking { store.settings.first().hiddenHomeCompetitions.containsAll(ids) } }
            back()
            rule.onNodeWithText("尚未选择首页赛事").assertExists()
            capture("empty")
            rule.onNodeWithTag("open-settings").performClick()
            choice("eng.1"); choice("f1")
            rule.waitUntil(10000) { runBlocking { store.settings.first().hiddenHomeCompetitions.intersect(ids.toSet()) == (ids.toSet() - setOf("eng.1", "f1")) } }
            back()
            rule.waitUntil(40000) { rule.onAllNodes(hasTestTagPrefix("home-round-")).fetchSemanticsNodes().isNotEmpty() }
            val cards = rule.onAllNodes(hasTestTagPrefix("home-round-")).fetchSemanticsNodes()
            check(cards.all { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].contains("英超") })
            check(cards.all { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].contains("第 ") })
            rule.onNodeWithContentDescription("英超 标识").assertExists()
            rule.onAllNodesWithText("查看足球赛程 ›").assertCountEquals(0)
            rule.onAllNodesWithText("深夜", substring = true).assertCountEquals(0)
            capture("selected")
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("f1-weekend"))
            rule.onNodeWithTag("f1-weekend-detail").assertDoesNotExist()
            rule.onAllNodesWithText("冲刺赛").assertCountEquals(0)
            rule.onAllNodesWithText("冲刺排位赛").assertCountEquals(0)
            rule.onNodeWithText("练习赛").assertExists()
            rule.onNodeWithText("排位赛").assertExists()
            capture("home-f1")
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10000) { rule.onAllNodesWithTag("open-settings").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("open-settings").performClick()
            rule.onNodeWithTag("home-visible-eng.1").assertIsOn()
            rule.onNodeWithTag("home-visible-esp.1").assertIsOff()
            back()
            rule.onNodeWithTag("nav-1").performClick()
            rule.onNodeWithTag("open-settings").assertDoesNotExist()
            rule.onNodeWithTag("football-season").assertExists()
            rule.onAllNodesWithText("ESPN", substring = true).assertCountEquals(0)
            capture("football")
            rule.onNodeWithTag("nav-2").performClick()
            rule.onNodeWithTag("open-settings").assertDoesNotExist()
            rule.onNodeWithTag("f1-season").assertExists()
            rule.onAllNodesWithText("Jolpica", substring = true).assertCountEquals(0)
            capture("f1")
            val after = runBlocking { store.settings.first() }
            assertEquals(before.homeTeam, after.homeTeam)
            assertEquals(before.favorites, after.favorites)
            assertEquals(before.followedCompetitions, after.followedCompetitions)
        } finally { runBlocking { ids.forEach { store.homeCompetition(it, it !in before.hiddenHomeCompetitions) } } }
    }
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
