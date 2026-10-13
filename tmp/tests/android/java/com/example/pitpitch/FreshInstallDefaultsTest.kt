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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run only against a separate, newly installed package; never clears the existing app. */
@RunWith(AndroidJUnit4::class)
class FreshInstallDefaultsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun firstLaunchAndRestartHaveDefaultsWithoutEditingSettings() {
        assumeTrue(rule.activity.packageName.endsWith(".defaultscheck"))
        val settings = runBlocking { UserStore(rule.activity).settings.first() }
        assertEquals(setOf("esp.1", "ita.1", "ger.1", "fra.1", "uefa.europa"), settings.hiddenHomeCompetitions)
        assertEquals(1_000_360, settings.homeTeam!!.id)
        rule.waitUntil(40000) { rule.onAllNodesWithContentDescription("主队：曼联，查看球队详情").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("主队：曼联，查看球队详情").assertExists()
        rule.waitUntil(40000) { rule.onAllNodesWithContentDescription("英超 标识").fetchSemanticsNodes().isNotEmpty() }
        capture("home")
        rule.onNodeWithTag("open-settings").performClick()
        for (id in listOf("eng.1", "uefa.champions", "f1", "esp.1", "ita.1", "ger.1", "fra.1", "uefa.europa")) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("home-visible-$id"))
            if (id in setOf("eng.1", "uefa.champions", "f1")) rule.onNodeWithTag("home-visible-$id").assertIsOn()
            else rule.onNodeWithTag("home-visible-$id").assertIsOff()
        }
        capture("settings")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("主队：曼联，查看球队详情").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(settings, runBlocking { UserStore(rule.activity).settings.first() })
    }
    private fun capture(name: String) {
        val file = File(rule.activity.cacheDir, "pitpitch-validation/fresh-defaults-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
