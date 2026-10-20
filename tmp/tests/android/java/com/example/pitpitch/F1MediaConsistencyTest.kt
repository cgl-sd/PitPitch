package com.example.pitpitch

import androidx.room.Room
import com.example.pitpitch.data.db.PitPitchDatabase
import com.example.pitpitch.data.db.FootballStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.*
import com.example.pitpitch.ui.SportsColors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class F1MediaConsistencyTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun prepare() = runBlocking { com.example.pitpitch.data.repository.localBackend(context).prepare() }
    private fun catalog() = JSONObject(context.assets.open("f1-media/catalog.json").bufferedReader().use { it.readText() })

    @Test fun driverTeamIdentityComesFromSourceAndRepairsOldCacheWithoutChangingFactsOrTime() = runBlocking {
        val archive = SportsArchive(context)
        val url = "https://api.jolpi.ca/ergast/f1/2020/driverstandings.json?limit=100"
        val (body, time) = requireNotNull(archive.response(url))
        val source = JolpicaAdapter.standings(body, false, 2020)
        assertEquals("mercedes", source.entries.first().constructorId)
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            val store = FootballStore(db)
            val old = source.copy(entries = source.entries.map { it.copy(constructorId = null) })
            store.writeStandings(2020, "driver", old, time)
            store.restoreStandingMediaIds(2020, "driver", source.entries)
            val restored = requireNotNull(store.f1Standings(2020, "driver", null))
            assertEquals(source, restored.first)
            assertEquals(time, restored.second)
            assertEquals(time, store.sourceTime("f1:2020:driver:${source.round}"))
        } finally { db.close() }
        val ambiguous = JSONObject(body)
        val teams = ambiguous.getJSONObject("MRData").getJSONObject("StandingsTable")
            .getJSONArray("StandingsLists").getJSONObject(0).getJSONArray("DriverStandings")
            .getJSONObject(0).getJSONArray("Constructors")
        teams.put(JSONObject(teams.getJSONObject(0).toString()).put("constructorId", "other-source-team"))
        assertNull(JolpicaAdapter.standings(ambiguous.toString(), false, 2020).entries.first().constructorId)
    }

    @Test fun everyLogoAndPortraitHasTheSameVisibleAnchorAndScaleAcrossAllSeasons() {
        val catalog = catalog()
        var logos = 0; var portraits = 0
        catalog.keys().forEach { key ->
            val record = catalog.getJSONObject(key)
            val role = record.getString("role")
            if (role != "logo" && role != "driver") return@forEach
            val bounds = requireNotNull(carImageBounds(record, if (role == "driver") "faceBounds" else "contentBounds"))
            val src = Size(record.getInt("width").toFloat(), record.getInt("height").toFloat())
            val dst = if (role == "driver") Size(60f, 64f) else Size(60f, 64f)
            val scale = if (role == "driver") PortraitContentScale(bounds).computeScaleFactor(src, dst)
                else LogoContentScale(bounds).computeScaleFactor(src, dst)
            assertEquals(key, scale.scaleX, scale.scaleY, .0001f)
            val scaled = IntSize((src.width * scale.scaleX).roundToInt(), (src.height * scale.scaleY).roundToInt())
            val alignment = if (role == "driver") PortraitContentAlignment(bounds) else CarContentAlignment(bounds)
            val offset = alignment.align(scaled, IntSize(dst.width.toInt(), dst.height.toInt()), LayoutDirection.Ltr)
            assertEquals(key, dst.width * .5f, offset.x + scaled.width * (bounds.left + bounds.right) * .5f, 1f)
            assertEquals(key, dst.height * (if (role == "driver") .45f else .5f),
                offset.y + scaled.height * (bounds.top + bounds.bottom) * .5f, 1f)
            if (role == "driver") {
                assertEquals(record.getString("sha256"), record.getString("faceGeometrySha256"))
                assertEquals(key, dst.height * .46f, src.height * (bounds.bottom - bounds.top) * scale.scaleY, .01f)
                portraits++
            } else {
                assertEquals(key, dst.width * dst.height * .20f,
                    src.width * src.height * (bounds.right - bounds.left) * (bounds.bottom - bounds.top) * scale.scaleX * scale.scaleY, .2f)
                logos++
            }
        }
        assertEquals(71, logos); assertTrue(portraits >= 150)
    }

    @Test fun everyConstructorUsesCenteredCompactGroupsAndUnclippedNamesAtNormalAndLargeFontSizes() {
        val archive = SportsArchive(context)
        val entries = (2020..2026).flatMap { year ->
            requireNotNull(archive.read("https://api.jolpi.ca/ergast/f1/$year/constructorstandings.json?limit=100") {
                JolpicaAdapter.standings(it, true, year)
            }).data.entries.map { year to it }
        }
        val drivers = (2020..2026).associateWith { year ->
            requireNotNull(archive.read("https://api.jolpi.ca/ergast/f1/$year/driverstandings.json?limit=100") {
                JolpicaAdapter.standings(it, false, year)
            }).data.entries
        }
        fun driver(entry: Pair<Int, RankingEntry>) = drivers.getValue(entry.first).first { it.constructorId == entry.second.constructorId }
        val current = mutableStateOf(entries.first())
        val width = mutableStateOf(360)
        val fontScale = mutableStateOf(1f)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.value)) {
                MaterialTheme(colorScheme = SportsColors) {
                    Column(Modifier.width(width.value.dp)) {
                        F1StandingRow(current.value.second, current.value.first, constructors = true)
                        F1StandingRow(driver(current.value), current.value.first)
                    }
                }
            }
        }
        for ((availableWidth, scale) in listOf(360 to 1f, 320 to 1.3f)) {
            rule.runOnIdle { width.value = availableWidth; fontScale.value = scale }
            var expected: List<Float>? = null
            for (entry in entries) {
                rule.runOnIdle { current.value = entry }
                val tag = "f1-constructor-${entry.first}-${entry.second.constructorId}"
                fun bounds(suffix: String) = rule.onNodeWithTag("$tag$suffix", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val card = bounds(""); val body = bounds("-body"); val logo = bounds("-logo"); val name = bounds("-name-slot")
                val identity = bounds("-identity"); val accent = bounds("-accent")
                val car = bounds("-car-slot"); val rank = bounds("-rank"); val points = bounds("-points")
                val driverTag = "f1-driver-${entry.first}-${driver(entry).driverId}"
                fun driverBounds(suffix: String) = rule.onNodeWithTag("$driverTag$suffix", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val driverStripe = driverBounds("-accent")
                val driverMedia = driverBounds("-media")
                assertEquals("$tag stripe aligns with driver stripe", driverStripe.left, accent.left, 1f)
                assertEquals("$tag stripe matches driver width", driverStripe.width, accent.width, 1f)
                assertEquals("$tag stripe matches driver height", driverStripe.height, accent.height, 1f)
                assertEquals("$tag media matches driver column", driverMedia.left, logo.left, 1f)
                assertEquals("$tag media matches driver width", driverMedia.width, logo.width, 1f)
                assertEquals("$tag media matches driver height", driverMedia.height, logo.height, 1f)
                assertEquals("$tag text matches driver column", driverBounds("-identity").left, identity.left, 1f)
                val driverPoints = driverBounds("-points")
                assertEquals("$tag points match driver column", driverPoints.left, points.left, 1f)
                if (scale == 1f) {
                    val pixels = rule.onNodeWithTag("$tag-accent", useUnmergedTree = true).captureToImage().toPixelMap()
                    val actual = pixels[pixels.width / 2, pixels.height / 2]
                    val expectedColor = constructorColor(entry.second.constructorId, entry.first)
                    assertEquals("$tag stripe red", expectedColor.red, actual.red, .01f)
                    assertEquals("$tag stripe green", expectedColor.green, actual.green, .01f)
                    assertEquals("$tag stripe blue", expectedColor.blue, actual.blue, .01f)
                }
                val pixelsPerDp = context.resources.displayMetrics.density
                if (scale == 1f) assertTrue("$tag normal row remains compact", card.height <= 94f * pixelsPerDp)
                assertEquals("$tag logo follows rank", 8f * pixelsPerDp, logo.left - rank.right, 1f)
                assertEquals("$tag stripe follows logo", 8f * pixelsPerDp, accent.left - logo.right, 1f)
                assertEquals("$tag name follows stripe", 8f * pixelsPerDp, identity.left - accent.right, 1f)
                assertEquals("$tag name and car share a left edge", name.left, car.left, 1f)
                val geometry = listOf(card.height, logo.left, logo.width, logo.height,
                    name.left, name.width, identity.top, identity.height, car.left, car.width, car.height,
                    rank.left, rank.width, points.right, points.top)
                expected?.zip(geometry)?.forEachIndexed { index, (a, b) -> assertEquals("$tag at $availableWidth/$scale geometry[$index]: $geometry", a, b, 1f) }
                expected = expected ?: geometry
                assertEquals("$tag rank is vertically centered", body.center.y, rank.center.y, 1f)
                assertEquals("$tag logo is vertically centered", body.center.y, logo.center.y, 1f)
                assertEquals("$tag points are vertically centered", body.center.y, points.center.y, 1f)
                assertEquals("$tag actual name/car group is vertically centered", body.center.y, (name.top + car.bottom) / 2f, 1f)
                assertEquals("$tag car is directly below its name", 2f * pixelsPerDp, car.top - name.bottom, 1f)
                rule.onNodeWithTag("$tag-name", useUnmergedTree = true).assertTextEquals(f1ConstructorDisplayName(entry.second.name))
                val layout = mutableListOf<TextLayoutResult>()
                rule.onNodeWithTag("$tag-name", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertFalse("$tag at $availableWidth/$scale must display full name; slot=$name, layout=${layout.single().size}, lines=${layout.single().lineCount}, widthOverflow=${layout.single().didOverflowWidth}, heightOverflow=${layout.single().didOverflowHeight}", layout.single().hasVisualOverflow)
                val pointsLayout = mutableListOf<TextLayoutResult>()
                rule.onNodeWithTag("$tag-points", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(pointsLayout) }
                assertFalse("$tag historical decimal points must fit completely", pointsLayout.single().hasVisualOverflow)
                val text = layout.single()
                for (line in 0 until text.lineCount) {
                    assertEquals("$tag name line $line stays left aligned above the car", 0f, text.getLineLeft(line), 1f)
                }
            }
        }
        assertEquals(71, entries.size)
    }
}
