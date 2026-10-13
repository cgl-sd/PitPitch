package com.example.pitpitch

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule

fun ComposeTestRule.chooseScheduleSeason(tag: String, year: Int) {
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    onNodeWithTag(tag).performClick()
    onNodeWithTag("$tag-option-$year").performClick()
    waitUntil(40000) { onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
    waitForIdle()

}

fun ComposeTestRule.scrollToScheduleTag(tag: String) {
    waitUntil(40000) { runCatching { onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false) }
}
