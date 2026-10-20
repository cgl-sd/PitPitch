package com.example.pitpitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.F1MediaImage
import com.example.pitpitch.feature.F1Screen
import com.example.pitpitch.feature.CircuitLayout
import com.example.pitpitch.ui.SportsColors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import android.util.Xml
import java.io.ByteArrayInputStream

class F1MediaTest {
    @org.junit.Before fun prepareBackend() = kotlinx.coroutines.runBlocking { com.example.pitpitch.data.repository.localBackend(context).prepare() }
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun state(year: Int): SportsViewModel.State {
        val archive = SportsArchive(context)
        val base = "https://api.jolpi.ca/ergast/f1/$year"
        return SportsViewModel.State(season = year,
            calendars = mapOf(year to archive.read("$base.json?limit=100") { F1Api.parseSeason(it, year) }!!.data),
            drivers = archive.read("$base/driverstandings.json?limit=100") { JolpicaAdapter.standings(it, false, year) },
            constructors = archive.read("$base/constructorstandings.json?limit=100") { JolpicaAdapter.standings(it, true, year) })
    }
    @Test fun packagedImagesDecodeAndCoverArchiveCircuitAndCarIdsWithoutSeasonFallback() {
        val catalog = context.assets.open("f1-media/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        catalog.keys().forEach { key ->
            val m = catalog.getJSONObject(key)
            val bytes = context.assets.open("f1-media/${m.getString("file")}").use { it.readBytes() }
            assertEquals(key, m.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            if (m.getString("file").endsWith(".svg")) {
                val parser = Xml.newPullParser()
                parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
                var paths = 0
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        assertFalse("No labels, raster images or scripts in circuit outlines", parser.name in setOf("text", "image", "script"))
                        if (parser.name == "path") paths++
                        if (parser.name == "svg") {
                            val view = parser.getAttributeValue(null, "viewBox").split(" ").map { it.toFloat() }
                            val width = parser.getAttributeValue(null, "width")?.toFloat() ?: view[2]
                            val height = parser.getAttributeValue(null, "height")?.toFloat() ?: view[3]
                            assertEquals(m.getInt("width"), width.toInt())
                            assertEquals(m.getInt("height"), height.toInt())
                        }
                    }
                }
                assertTrue(paths > 0)
            } else {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull(key, bitmap)
                assertEquals(m.getInt("width"), bitmap.width); assertEquals(m.getInt("height"), bitmap.height); bitmap.recycle()
            }
            assertFalse("Never bundle a CDN default silhouette", m.getString("url").contains("d_common:"))
        }
        for (year in 2020..2026) {
            val s = state(year)
            s.races.forEach {
                val record = catalog.optJSONObject("layout/$year/${it.id}")
                assertNotNull("$year ${it.id}", record)
                assertNotNull(it.circuit, F1Media.layout(context, it))
                assertEquals(it.circuitId, record!!.getString("circuitId"))
                assertEquals(it.round, record.getInt("round"))
                assertNull(F1Media.layout(context, it.copy(circuitId = "unknown")))
            }
            s.constructors!!.data.entries.forEach {
                assertNotNull("$year ${it.name} logo", F1Media.asset(context, "logo", it.constructorId, year))
                assertNotNull("$year ${it.name} car", F1Media.asset(context, "car", it.constructorId, year))
                val m = catalog.getJSONObject("car/$year/${it.constructorId}")
                assertEquals(year, m.getInt("season")); assertEquals(it.constructorId, m.getString("entity"))
            }
            s.drivers!!.data.entries.forEach {
                assertNotNull("$year ${it.name} photo", F1Media.asset(context, "driver", it.driverId, year))
            }
        }
        assertNull(F1Media.asset(context, "car", "cadillac", 2020))
        assertNull(F1Media.asset(context, "car", "sauber", 2026))
        assertNull(F1Media.asset(context, "driver", "unknown", 2026))
        assertNull(F1Media.asset(context, "circuit", "../marina_bay"))
        // The standard Bahrain outline must not be reused for the outer Sakhir course.
        val bahrain = catalog.getJSONObject("layout/2020/f1-2020-15")
        val sakhir = catalog.getJSONObject("layout/2020/f1-2020-16")
        assertNotEquals("Outer Sakhir layout must not reuse the standard Bahrain shape",
            bahrain.getString("sha256"), sakhir.getString("sha256"))
        val race = state(2026).races.first()
        assertNull(F1Media.layout(context, race.copy(season = 2019)))
    }
    @Test fun actualCalendarAndStandingsShowLoadedPhotosAndKeepPublishedPoints() {
        val s = mutableStateOf(state(2026))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            F1Screen(s.value, ZoneId.of("Asia/Shanghai"), {}, {}, { s.value = state(it) }, {})
        } }
        val race = s.value.races.first()
        rule.scrollToScheduleTag("race-${race.id}")
        photo("${race.circuit} 赛道布局图")
        capture("f1-media-calendar")
        top(); rule.onNodeWithTag("f1-section-1").performClick()
        val driver = s.value.drivers!!.data.entries.first()
        photo("${driver.name} 车手照片")
        rule.onNodeWithText(driver.points).assertExists()
        capture("f1-media-drivers")
        top(); rule.onNodeWithTag("f1-section-2").performClick()
        val constructor = s.value.constructors!!.data.entries.first()
        photo("${constructor.name} 车队标识")
        photo("${constructor.name} 2026 赛季赛车")
        rule.onNodeWithText(constructor.points).assertExists()
        val tag = "f1-constructor-2026-${constructor.constructorId}"
        val stripe = rule.onNodeWithTag("$tag-accent", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val identity = rule.onNodeWithTag("$tag-identity", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Identity starts immediately after the team stripe", 8f * context.resources.displayMetrics.density,
            identity.left - stripe.right, 1f)
        capture("f1-media-constructors")
        top(); rule.onNodeWithTag("f1-season").performClick()
        rule.onNodeWithTag("f1-season-option-2020").performClick()
        val old = s.value.constructors!!.data.entries.first()
        photo("${old.name} 2020 赛季赛车")
        rule.onAllNodesWithContentDescription("${old.name} 2026 赛季赛车").assertCountEquals(0)
        rule.onNodeWithText(old.points).assertExists()
        capture("f1-media-constructors-2020")
    }
    @Test fun allSeasonCarsUseThe2026VisibleWidthWithoutStretching() {
        val catalog = context.assets.open("f1-media/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        val keys = catalog.keys().asSequence().filter { it.startsWith("car/") }.sorted().toList()
        val current = mutableStateOf(keys.first())
        val parent = Color(0xFFFF00FF)
        rule.setContent {
            val record = catalog.getJSONObject(current.value)
            Box(Modifier.size(136.dp, 34.dp).background(parent)) {
                F1MediaImage("car", record.getString("entity"), record.getInt("season"),
                    current.value, Modifier.fillMaxSize())
            }
        }
        val widths = mutableListOf<Int>()
        for (key in keys) {
            rule.runOnUiThread { current.value = key }
            photo(key)
            val record = catalog.getJSONObject(key)
            val pixels = rule.onNodeWithTag("f1-media-car-${record.getInt("season")}-${record.getString("entity")}")
                .captureToImage().toPixelMap()
            val columns = (0 until pixels.width).filter { x ->
                (0 until pixels.height).any { y ->
                    val color = pixels[x, y]
                    kotlin.math.abs(color.red - parent.red) + kotlin.math.abs(color.green - parent.green) +
                        kotlin.math.abs(color.blue - parent.blue) > .15f
                }
            }
            assertTrue("$key must fill the same visible width as 2026", columns.isNotEmpty())
            val width = columns.last() - columns.first() + 1
            assertTrue("$key visible width $width/${pixels.width}", width >= pixels.width * .94f)
            widths += width
        }
        assertTrue("All 71 bodies must have consistent visible width", widths.max() - widths.min() <= widths.max() * .05f)
    }
    @Test fun circuitOutlineKeepsItsTransparentCanvasOnAColoredParent() {
        val race = state(2026).races.first()
        val parent = Color(0xFFD6E4F1)
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            Box(Modifier.size(128.dp, 96.dp).background(parent)) {
                CircuitLayout(race, Modifier.fillMaxSize())
            }
        } }
        photo("${race.circuit} 赛道布局图")
        val pixels = rule.onNodeWithTag("f1-media-layout-${race.season}-${race.id}").captureToImage().toPixelMap()
        for ((x, y) in listOf(2 to 2, pixels.width - 3 to 2, 2 to pixels.height - 3, pixels.width - 3 to pixels.height - 3))
            assertEquals("Track canvas must expose the parent's background", parent.toArgb(), pixels[x, y].toArgb())
    }
    @Test fun everyArchivedRaceRendersItsBoundCircuitOutline() {
        val races = (2020..2026).flatMap { state(it).races }
        val current = mutableStateOf(races.first())
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            Box(Modifier.size(220.dp, 160.dp).background(Color(0xFFD6E4F1))) {
                CircuitLayout(current.value, Modifier.fillMaxSize())
            }
        } }
        for (race in races) {
            rule.runOnUiThread { current.value = race }
            photo("${race.circuit} 赛道布局图")
            val pixels = rule.onNodeWithTag("f1-media-layout-${race.season}-${race.id}").captureToImage().toPixelMap()
            val hasTrackStroke = (0 until pixels.height step 3).any { y ->
                (0 until pixels.width step 3).any { x ->
                    val color = pixels[x, y]
                    color.alpha > .9f && color.red < .15f && color.green < .15f && color.blue < .15f
                }
            }
            assertTrue("${race.season} ${race.id} must render its actual track rather than a blank canvas", hasTrackStroke)
            rule.onAllNodes(hasContentDescription("图片暂缺", substring = true), useUnmergedTree = true).assertCountEquals(0)
        }
    }
    @Test fun expandedCalendarRaceUsesTheSameWhiteBackgroundAsOtherRows() {
        val calendar = state(2026)
        val focus = (f1UpcomingRace(calendar.races.sortedBy { it.round }, java.time.Instant.now(), ZoneId.systemDefault())
            ?: calendar.races.minByOrNull { it.round })!!
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                F1Screen(calendar, ZoneId.systemDefault(), {}, {}, {}, {})
            }
        } }
        rule.waitUntil(20000) { rule.onAllNodesWithTag("f1-calendar-expanded-${focus.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.scrollToScheduleTag("race-${focus.id}")
        val pixels = rule.onNodeWithTag("race-${focus.id}").captureToImage().toPixelMap()
        assertEquals("Expanded race must not have a tinted highlight", Color.White.toArgb(),
            pixels[pixels.width - 16, pixels.height / 2].toArgb())
    }
    private fun photo(description: String) {
        rule.waitUntil(20000) { rule.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun top() { rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0) }
    private fun capture(name: String) {
        rule.waitForIdle()
        val file = File(context.cacheDir, "pitpitch-validation/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
