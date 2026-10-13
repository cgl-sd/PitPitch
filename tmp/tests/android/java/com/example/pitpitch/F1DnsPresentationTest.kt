package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.feature.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class F1DnsPresentationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun realChinaResultsShowDnsSeparatelyFromRetirements() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = SportsArchive(context)
        val rows = archive.read("https://api.jolpi.ca/ergast/f1/2026/2/results.json?limit=100") {
            JolpicaAdapter.resultsForRound(it, 2026, 2)
        }!!.data.takeLast(6)
        rule.setContent { MaterialTheme { Column { F1SessionResultHeader(true); rows.forEach { RaceResultRow(it, 2026) } } } }
        rule.onAllNodesWithText("未发车").assertCountEquals(4)
        rule.onAllNodesWithText("退赛").assertCountEquals(2)
        rule.onNodeWithText("未出赛").assertDoesNotExist()
        rows.forEach {
            rule.onNodeWithTag("f1-race-result-2026-${it.driverId}-points").assertTextEquals("0")
            rule.onNodeWithTag("f1-race-result-2026-${it.driverId}-team-color").assertExists()
        }
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File(context.cacheDir, "pitpitch-validation/f1-china-dns.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
