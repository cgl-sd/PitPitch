package com.example.pitpitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.pitpitch.data.BundledTeamBadges
import com.example.pitpitch.ui.components.TeamBadge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BundledTeamBadgesTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Test fun everyPackagedCrestDecodesAndKeepsSourceDimensions() {
        val catalog = JSONObject(context.assets.open("team-badges/catalog.json").bufferedReader().use { it.readText() })
        assertTrue(catalog.length() >= 200)
        for (url in catalog.keys()) {
            val entry = catalog.getJSONObject(url)
            val asset = BundledTeamBadges.asset(context, url)!!
            val bytes = context.assets.open(asset.removePrefix("file:///android_asset/")).use { it.readBytes() }
            assertEquals(entry.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(url, bitmap)
            assertEquals(entry.getInt("width"), bitmap.width)
            assertEquals(entry.getInt("height"), bitmap.height)
            bitmap.recycle()
        }
        assertNull(BundledTeamBadges.asset(context, null))
        assertNull(BundledTeamBadges.asset(context, "https://example.com/unknown-team.png"))
    }
    @Test fun freshImageLoaderLoadsRealCrestsWithAllHttpRequestsRejected() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { throw AssertionError("Bundled crest attempted network") }.build()
        val loader = ImageLoader.Builder(context).okHttpClient(http).memoryCache(null).diskCache(null).build()
        try {
            // Independent loader and no caches: success must come from the APK assets.
            for (id in listOf(359, 360, 367, 364)) {
                val source = "https://a.espncdn.com/i/teamlogos/soccer/500/$id.png"
                val local = BundledTeamBadges.asset(context, source)!!
                val result = loader.execute(ImageRequest.Builder(context).data(local).allowHardware(false).build())
                assertTrue(source, result is SuccessResult)
            }
        } finally { loader.shutdown() }
    }
    @Test fun realTeamBadgesRenderWithBundledAssets() {
        val clubs = listOf("Arsenal" to 359, "Manchester United" to 360, "Tottenham" to 367, "Chelsea" to 363)
        rule.setContent { Column { clubs.forEach { (name, id) -> TeamBadge(name, 1000000 + id, "https://a.espncdn.com/i/teamlogos/soccer/500/$id.png") } } }
        rule.waitUntil(10000) { clubs.all { (name, _) -> rule.onAllNodesWithContentDescription("$name 队徽").fetchSemanticsNodes().isNotEmpty() } }
        clubs.forEach { (name, _) -> rule.onNodeWithContentDescription("$name 队徽").assertIsDisplayed() }
        val file = File(context.cacheDir, "pitpitch-validation/bundled-team-badges.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
