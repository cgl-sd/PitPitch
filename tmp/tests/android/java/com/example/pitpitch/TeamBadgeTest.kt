package com.example.pitpitch

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.decode.DataSource
import com.example.pitpitch.ui.components.TeamBadge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class TeamBadgeTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun missingAndFailedCrestsKeepAccessiblePlaceholders() {
        rule.setContent { Row {
            TeamBadge("缺失队", null, null)
            TeamBadge("失败队", null, "https://127.0.0.1:1/missing.svg")
        } }
        rule.onNodeWithContentDescription("缺失队 队徽占位图").assertIsDisplayed()
        rule.onNodeWithContentDescription("失败队 队徽占位图").assertIsDisplayed()
    }
    @Test fun providerPngAndSvgCrestsRenderAndCanBeReadFromDiskCache(): Unit = runBlocking {
        // Provider crests outside the bundled catalog still render over the network and reach the disk cache.
        val png = "https://a.espncdn.com/i/teamlogos/soccer/500/359.png"
        val svg = "https://a.espncdn.com/i/teamlogos/soccer/500/368.png"
        val loader = context.imageLoader
        for (url in listOf(png, svg)) {
            val request = ImageRequest.Builder(context).data(url).size(160).build()
            val result = loader.execute(request)
            assertTrue("Crest failed: $url: ${(result as? coil.request.ErrorResult)?.throwable}", result is SuccessResult)
            loader.memoryCache?.clear()
            val cached = loader.execute(request)
            assertTrue(cached is SuccessResult)
            assertEquals(DataSource.DISK, (cached as SuccessResult).dataSource)
        }
        rule.setContent { Row { TeamBadge("Dortmund", null, png); TeamBadge("HSV", null, svg) } }
        rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("HSV 队徽").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Dortmund 队徽").assertIsDisplayed()
        rule.onNodeWithContentDescription("HSV 队徽").assertIsDisplayed()
        val file = File(context.cacheDir, "pitpitch-validation/v051-badges.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
