package com.example.pitpitch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.metrics.performance.JankStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.Collections

/** Debug emulator sample only: do not use as a release or real-device performance gate. */
@RunWith(AndroidJUnit4::class)
class PerformanceSampleTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun sampleTabAndListFramesWithJankStats() {
        rule.waitUntil(10000) { rule.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty() }
        val frames = Collections.synchronizedList(mutableListOf<Pair<Long, Boolean>>())
        lateinit var tracker: JankStats
        rule.activityRule.scenario.onActivity { activity -> tracker = JankStats.createAndTrack(activity.window) { frame -> frames.add(frame.frameDurationUiNanos to frame.isJank) } }
        try {
            repeat(5) { (0..2).forEach { rule.onNodeWithTag("nav-$it").performClick() } }
            rule.onNodeWithTag("nav-1").performClick()
            repeat(3) { rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(); swipeDown() } }
            // Confirm accessibility semantics of selection for the grouped tabs.
            rule.onNodeWithTag("nav-0").performClick(); rule.onNodeWithTag("nav-0").assertIsSelected()
        } finally { rule.activityRule.scenario.onActivity { tracker.isTrackingEnabled = false } }
        val copy = synchronized(frames) { frames.toList() }
        assertTrue("Frame callbacks must be observed", copy.isNotEmpty())
        val times = copy.map { it.first / 1_000_000.0 }.sorted()
        val report = JSONObject().put("kind", "JankStats Debug emulator sample").put("frames", copy.size)
            .put("jankyFrames", copy.count { it.second }).put("jankPercent", 100.0 * copy.count { it.second } / copy.size)
            .put("p95UiMs", times[(times.size * .95).toInt().coerceAtMost(times.lastIndex)])
            .put("maxUiMs", times.last()).put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pitpitch-validation").apply { mkdirs() }
        File(directory, "v05-jankstats.json").writeText(report.toString(2))
    }
}
