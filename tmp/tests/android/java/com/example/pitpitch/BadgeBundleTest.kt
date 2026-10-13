package com.example.pitpitch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.BadgeCatalog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Bundled crest assets must win over provider URLs; unknown teams and demo teams keep the URL/placeholder path. */
@RunWith(AndroidJUnit4::class)
class BadgeBundleTest {
    @Test fun bundledCrestsResolveBeforeProviderUrlsAndUnknownTeamsFallBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundled = BadgeCatalog.uri(context, 359, "https://a.espncdn.com/i/teamlogos/soccer/500/359.png")
        assertTrue("Arsenal crest must resolve to the bundled asset, got $bundled", bundled!!.startsWith("file:///android_asset/badges/359"))
        val fallback = BadgeCatalog.uri(context, 42424242, "https://a.espncdn.com/i/teamlogos/soccer/500/42424242.png")
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/42424242.png", fallback)
        assertNull(BadgeCatalog.uri(context, null, null))
        val manifest = context.assets.open("badges/manifest.json").bufferedReader().use { it.readText() }
        assertTrue(manifest.contains("\"stored\""))
    }
}
