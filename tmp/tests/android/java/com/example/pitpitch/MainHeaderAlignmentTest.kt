package com.example.pitpitch

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainHeaderAlignmentTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun footballAndF1UseMatchingBrandAndFirstToolbarGeometry() {
        // Exercise the actual app without changing leagues, seasons, preferences or stored data.
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-1").performClick()
        val football = headerGeometry("football-brand", "football-leagues")

        rule.onNodeWithTag("nav-2").performClick()
        val f1 = headerGeometry("f1-brand", "f1-controls")

        assertEquals("Brand canvases must have the same height", football.brand.height, f1.brand.height, 1f)
        assertEquals("Brands must share a vertical center", football.brand.center.y, f1.brand.center.y, 1f)
        assertEquals("F1 must not add padding above its first selector row", football.toolbar.top, f1.toolbar.top, 1f)
        assertEquals("First selector text must start at the same height", football.textTop, f1.textTop, 1f)
        assertEquals("First selector text must share a baseline", football.textBaseline, f1.textBaseline, 1f)

        val source = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(rule.activity.resources, R.drawable.ic_f1_wordmark, source)
        assertTrue("Bundled F1 artwork must expose its native dimensions", source.outWidth > 0 && source.outHeight > 0)
        val nativeRatio = source.outWidth.toFloat() / source.outHeight
        assertEquals("F1 canvas must retain native proportions within independent pixel rounding",
            f1.brand.height * nativeRatio, f1.brand.width, (nativeRatio + 1f) / 2f)
    }

    @Test fun bothSeasonSelectorsUsePlainBackgroundAndMatchTheirSectionTextBaseline() {
        rule.waitUntil(20000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        for ((tab, prefix) in listOf(1 to "football", 2 to "f1")) {
            rule.onNodeWithTag("nav-$tab").performClick()
            rule.onNodeWithTag("$prefix-section-0").performClick()
            val button = rule.onNodeWithTag("$prefix-season").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertEquals("Season label must align with its section label",
                labelBaseline("$prefix-section-0"), labelBaseline("$prefix-season"), 1f)
            val pixels = button.captureToImage().toPixelMap()
            for ((x, y) in listOf(2 to pixels.height / 2, pixels.width - 3 to pixels.height / 2, pixels.width / 2 to 2)) {
                assertEquals("Season selector must not paint a rectangular highlight", Color.White.toArgb(), pixels[x, y].toArgb())
            }
        }
    }

    private fun labelBaseline(tag: String): Float {
        val node = rule.onNode(hasAnyAncestor(hasTestTag(tag)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
    }

    private fun headerGeometry(brandTag: String, toolbarTag: String): HeaderGeometry {
        rule.waitUntil(20000) { rule.onAllNodesWithTag(toolbarTag).fetchSemanticsNodes().isNotEmpty() }
        val brand = rule.onNodeWithTag(brandTag).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val topbar = rule.onNodeWithTag("main-topbar").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val toolbar = rule.onNodeWithTag(toolbarTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = rule.onNodeWithTag("main-page-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals("Brand must remain centered in the shared top bar", topbar.center.y, brand.center.y, 1f)
        assertEquals("Page must start immediately below the top bar", topbar.bottom, viewport.top, 1f)
        assertEquals("First selector row must start at the page's usable top", viewport.top, toolbar.top, 1f)

        val text = rule.onAllNodes(
            hasAnyAncestor(hasTestTag(toolbarTag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true
        ).fetchSemanticsNodes().filter {
            it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
        }.minBy { it.boundsInRoot.left }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNode(SemanticsMatcher("first visible selector label") { it.id == text.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                assertTrue("Selector label must expose its live text layout", getLayout(layouts))
            }
        assertEquals("Selector label must have exactly one text layout", 1, layouts.size)
        return HeaderGeometry(brand, toolbar, text.boundsInRoot.top,
            text.boundsInRoot.top + layouts.single().firstBaseline)
    }

    private data class HeaderGeometry(
        val brand: Rect,
        val toolbar: Rect,
        val textTop: Float,
        val textBaseline: Float
    )
}
