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
        val body = com.example.pitpitch.data.JsonClient().load("https://api.openligadb.de/getmatchdata/bl1/2025/1") { it }.data
        val matches = com.example.pitpitch.data.OpenLigaAdapter.matches(body)
        val urls = matches.flatMap { listOfNotNull(it.homeBadgeUrl, it.awayBadgeUrl) }
        val png = urls.first { it.endsWith("Borussia_Dortmund_logo.svg.png") }
        val svg = urls.first { it.endsWith("Hamburger_SV_logo.svg") }
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
