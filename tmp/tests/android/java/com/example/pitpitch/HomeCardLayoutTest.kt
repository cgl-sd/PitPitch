package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.HomeFootballRound
import com.example.pitpitch.data.sampleMatches
import com.example.pitpitch.feature.HomeFootballRoundCard
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

class HomeCardLayoutTest {
    @get:Rule val rule = createComposeRule()
    @Test fun oneTwoThreeMatchCardsHaveEqualBottomSpacingAndNeverShowAFourthMatch() {
        // Controlled fixtures only in the test; no production data or preferences are changed.
        val matches = (0..3).map { index -> sampleMatches.first().copy(id = "card-spacing-$index",
            competition = "欧冠", competitionId = "uefa.champions", roundNumber = 2, stage = "联赛阶段",
            kickoffUtc = Instant.now().plusSeconds(3600L * (index + 1)).toString(), status = "待赛", isDemo = false) }
        val round = mutableStateOf(HomeFootballRound("欧冠", "联赛阶段", matches.take(1), preview = true))
        var opened: String? = null
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            HomeFootballRoundCard(round.value, ZoneId.systemDefault()) { opened = it }
        } }
        for (count in 1..4) {
            rule.runOnIdle { round.value = round.value.copy(matches = matches.take(count)) }
            val visible = matches.take(count.coerceAtMost(3))
            rule.onNodeWithText("下一轮").assertDoesNotExist()
            rule.onNodeWithText("后续赛程").assertDoesNotExist()
            rule.onNodeWithText("第 2 轮").assertExists()
            val card = rule.onNodeWithTag("home-round-${round.value.key}").fetchSemanticsNode().boundsInRoot
            val last = visible.last()
            val lastRow = rule.onNodeWithTag("match-${last.id}").fetchSemanticsNode().boundsInRoot
            val lastName = rule.onNodeWithTag("fixture-home-name-${last.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("No extra footer padding", card.bottom, lastRow.bottom, 1f)
            val finalInset = card.bottom - lastName.center.y
            visible.dropLast(1).forEach { match ->
                val row = rule.onNodeWithTag("match-${match.id}").fetchSemanticsNode().boundsInRoot
                val name = rule.onNodeWithTag("fixture-home-name-${match.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val separator = rule.onNodeWithTag("home-divider-${match.id}").fetchSemanticsNode().boundsInRoot
                assertEquals("Every match row has equal height", lastRow.height, row.height, 1f)
                assertEquals("Card edge replaces the final separator with equal spacing", finalInset, separator.top - name.center.y, 1f)
            }
            rule.onNodeWithTag("home-divider-${last.id}").assertDoesNotExist()
            rule.onNodeWithTag("match-${matches.last().id}").assertDoesNotExist()
        }
        rule.onNodeWithTag("match-${matches[2].id}").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * .1f, height * .2f))
        }
        assertEquals(matches[2].id, opened)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "pitpitch-validation/home-card-equal-spacing.png"); file.parentFile!!.mkdirs()
        file.outputStream().use {
            rule.onNodeWithTag("home-round-${round.value.key}").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
