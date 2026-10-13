package com.example.pitpitch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.pitpitch.ui.SportsColors
import com.example.pitpitch.ui.components.Segments
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CompactControlsAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun largePhaseLabelsRemainReadableAndPaddingStillSelectsOneTab() {
        val labels = listOf("联赛阶段", "淘汰赛", "资格赛/其他")
        rule.setContent {
            val density = LocalDensity.current.density
            var selected by remember { mutableIntStateOf(0) }
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                MaterialTheme(colorScheme = SportsColors) {
                    Box(Modifier.width(280.dp)) { Segments(labels, selected, { selected = it }) }
                }
            }
        }
        for (label in labels) {
            rule.onNode(hasText(label) and isSelectable()).assertHeightIsAtLeast(48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("Label '$label': size=${layout.size}, lines=${layout.lineCount}, " +
                "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
        }
        rule.onNode(hasText(labels[2]) and isSelectable()).performTouchInput {
            click(Offset(width / 2f, 1f))
        }.assertIsSelected()
        rule.onNode(hasText(labels[0]) and isSelectable()).assertIsNotSelected()
        rule.onAllNodes(isSelected()).assertCountEquals(1)
    }
}
