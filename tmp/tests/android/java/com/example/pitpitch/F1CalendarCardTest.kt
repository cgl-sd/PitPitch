package com.example.pitpitch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.pitpitch.data.F1Race
import com.example.pitpitch.data.F1Session
import com.example.pitpitch.feature.CalendarRaceRow
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class F1CalendarCardTest {
    @get:Rule val rule = createComposeRule()
    @Test fun narrowCardKeepsLocalDatesAndTimesCompleteWithLargeFonts() = verifyColumns(280, 1.6f)
    @Test fun regularCardKeepsEqualColumnsFontsAndEdgeInsets() = verifyColumns(400, 1f)

    @Test fun compactSeparatorsInsetTheirEndsWithoutNarrowingRaceCards() {
        val race = F1Race(2026, 18, "United States Grand Prix", "Circuit of the Americas", "2026-10-25", "20:00:00Z", "USA", emptyList())
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            androidx.compose.foundation.layout.Column(Modifier.width(400.dp)) {
                CalendarRaceRow(race, ZoneId.of("Asia/Shanghai"), false, {})
            }
        } }
        rule.onNodeWithTag("race-${race.id}").assertWidthIsEqualTo(400.dp)
        rule.onNodeWithTag("f1-race-divider-${race.id}").assertWidthIsEqualTo(380.dp)
    }

    private fun verifyColumns(width: Int, fontScale: Float) {
        val race = F1Race(2026, 18, "United States Grand Prix", "Circuit of the Americas", "2026-10-25", "20:00:00Z", "USA",
            listOf(F1Session("第一次练习赛", "2026-10-23", "17:30:00Z"), F1Session("排位赛", "2026-10-24", "21:00:00Z")))
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme(colorScheme = SportsColors) {
                    Box(Modifier.width(width.dp)) { CalendarRaceRow(race, ZoneId.of("Asia/Shanghai"), true, {}) }
                }
            }
        }
        for (text in listOf("练习赛", "排位赛", "正赛", "10月24日 周六", "10月25日 周日", "10月26日 周一", "01:30", "05:00", "04:00")) {
            val node = rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("$text must fit without truncation: size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}, style=${layout.layoutInput.style}, constraints=${layout.layoutInput.constraints}", layout.hasVisualOverflow)
        }
        val cells = listOf("第一次练习赛", "排位赛", "正赛").map {
            rule.onNodeWithTag("f1-session-${race.id}-$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        for (cell in cells) assertEquals("All session columns share one width", cells.first().width, cell.width, 1f)
        assertEquals("Column gutters match", cells[1].left - cells[0].right, cells[2].left - cells[1].right, 1f)
        for (tier in listOf(listOf("练习赛", "排位赛", "正赛"), listOf("10月24日 周六", "10月25日 周日", "10月26日 周一"), listOf("01:30", "05:00", "04:00"))) {
            val sizes = tier.map { text ->
                val result = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                result.single().layoutInput.style.fontSize
            }
            assertEquals("Corresponding text uses one font size", 1, sizes.distinct().size)
        }
        rule.onNodeWithTag("race-${race.id}").assertWidthIsEqualTo(width.dp)
        val title = rule.onNodeWithText("正赛", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bell = rule.onNodeWithTag("reminder-${race.id}").fetchSemanticsNode().boundsInRoot
        val date = rule.onNodeWithText("10月26日 周一", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Bell belongs to the race title row", bell.left >= title.right - 1f)
        assertEquals("Bell and title share the same vertical center", title.center.y, bell.center.y, 1f)
        assertTrue("Bell icon sits above the date", bell.center.y < date.top)
        assertEquals("Bell touch target aligns with the last column edge", cells.last().right, bell.right, 1f)
        val card = rule.onNodeWithTag("race-${race.id}").fetchSemanticsNode().boundsInRoot
        assertEquals("Outer content insets match", cells.first().left - card.left, card.right - cells.last().right, 1f)
        if (fontScale == 1f) {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File(context.cacheDir, "pitpitch-validation/f1-equal-columns.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.onAllNodesWithTag("reminder-${race.id}").assertCountEquals(1)
        rule.onNodeWithTag("reminder-${race.id}").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
}
