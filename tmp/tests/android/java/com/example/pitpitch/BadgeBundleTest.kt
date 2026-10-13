package com.example.pitpitch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.BundledTeamBadges
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Bundled crest assets must be indexed by exact URL; unknown crests must not borrow another team's artwork. */
@RunWith(AndroidJUnit4::class)
class BadgeBundleTest {
    @Test fun bundledCrestsAreIndexedByUrlAndUnknownTeamsFallBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = JSONObject(context.assets.open("team-badges/catalog.json").bufferedReader().use { it.readText() })
        val arsenal = catalog.getJSONObject("https://a.espncdn.com/i/teamlogos/soccer/500/359.png")
        assertTrue("Arsenal must be indexed by its provider URL", arsenal.getJSONObject("teams").has("359"))
        val bytes = context.assets.open("team-badges/" + arsenal.getString("file")).use { it.readBytes() }
        assertTrue("bundled crest must be a real image, was ${bytes.size} bytes", bytes.size > 1000)
        assertNull("unknown crest must not resolve to another team's artwork",
            BundledTeamBadges.asset(context, "https://a.espncdn.com/i/teamlogos/soccer/500/42424242.png"))
    }
}
