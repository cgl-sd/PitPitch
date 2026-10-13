package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pitpitch.data.local.UserStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun threeTabsAndCenteredTeamCrestPersistAfterRecreation() {
        val store = UserStore(rule.activity)
        val before = runBlocking { store.settings.first() }
        try {
            rule.waitUntil(10000) { rule.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav-3").assertDoesNotExist(); rule.onNodeWithTag("nav-4").assertDoesNotExist()
            rule.onNodeWithText("我的").assertDoesNotExist(); rule.onNodeWithText("收藏").assertDoesNotExist()
            for (i in listOf(1, 2, 0)) { rule.onNodeWithTag("nav-$i").performClick(); rule.onNodeWithTag("nav-$i").assertIsSelected() }
            rule.onNodeWithTag("open-settings").performClick()
            rule.onNodeWithTag("settings-select-team").performClick()
            rule.waitUntil(40000) { runCatching { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("team-1000359")); true }.getOrDefault(false) }
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("team-1000359"))
            rule.onNodeWithTag("team-1000359").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithTag("settings-select-team").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("返回上一级").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("主队：阿森纳，查看球队详情").fetchSemanticsNodes().isNotEmpty() }
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10000) { rule.onAllNodesWithTag("nav-team").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("主队：阿森纳，查看球队详情").assertExists()
            rule.onNodeWithText("主队").assertDoesNotExist()
            val button = rule.onNodeWithTag("nav-team").fetchSemanticsNode().boundsInRoot
            val artwork = rule.onNodeWithTag("home-team-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(button.center.x, artwork.center.x, 1f)
            assertEquals(button.center.y, artwork.center.y, 1f)
            val file = File(rule.activity.cacheDir, "pitpitch-validation/v052-navigation.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            rule.onNodeWithTag("open-settings").performClick(); rule.onNodeWithTag("home-visible-eng.1").assertExists()
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithTag("nav-team").assertExists()
        } finally { runBlocking { store.homeTeam(before.homeTeam) } }
    }
}
