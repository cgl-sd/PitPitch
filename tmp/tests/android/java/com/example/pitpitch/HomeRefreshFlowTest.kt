package com.example.pitpitch

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.example.pitpitch.app.SportsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real bottom navigation and VM, including a refresh already running in the background. */
class HomeRefreshFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun oneHomeTapDoesNotRefreshAndADoubleTapSharesThePullAnimation() {
        val model = ViewModelProvider(rule.activity)[SportsViewModel::class.java]
        rule.waitUntil(30000) { model.state.value.homeReady }
        rule.onNodeWithTag("nav-0").performClick()
        rule.runOnIdle { assertFalse(model.state.value.homeRefreshing) }
        android.os.SystemClock.sleep(400)
        val before = rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("nav-0").performClick()
        rule.onNodeWithTag("nav-0").performClick()
        rule.runOnIdle { assertTrue(model.state.value.homeRefreshing) }
        assertTrue(rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top > before)
        rule.waitUntil(60000) { !model.state.value.homeRefreshing }
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithText("赛事赛程").fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.onNodeWithTag("nav-0").assertIsSelected()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("home-updated"))
        val updated = model.state.value.homeUpdatedUtc
        assertNotNull("a successful refresh must record a source time", updated)
        assertEquals(runBlocking { com.example.pitpitch.data.db.FootballStore(
            com.example.pitpitch.data.db.PitPitchDatabase.build(rule.activity)).homeUpdated() }, updated)
        rule.onNodeWithTag("home-updated").assertTextEquals(
            "上次更新：${com.example.pitpitch.feature.displayInstant(updated, java.time.ZoneId.systemDefault())}")
        val screenshot = rule.onRoot().captureToImage()
        val file = java.io.File(rule.activity.cacheDir, "pitpitch-validation/home-updated-footer.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { screenshot.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
