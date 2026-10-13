package com.example.pitpitch

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.EspnFootballAdapter
import com.example.pitpitch.data.SportsArchive
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.data.sampleMatches
import com.example.pitpitch.feature.FootballFixtureRow
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** A provider that stopped a match without a result is never shown as an upcoming, bookable fixture. */
@RunWith(AndroidJUnit4::class)
class ProviderStoppedFixtureTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun stoppedFixtureShowsItsRealStateAndOnlyPendingFixturesOfferTheBell() {
        val now = Instant.now()
        val stopped = sampleMatches.first().copy(id = "fb-test-stopped", competitionId = "eng.1", status = "终止", score = "— : —", isDemo = false)
        val pending = sampleMatches.first().copy(id = "fb-test-pending", competitionId = "eng.1", status = "待赛", score = "— : —",
            kickoffUtc = now.plusSeconds(24 * 3600).toString(), isDemo = false)
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            Column {
                FootballFixtureRow(stopped, ZoneId.systemDefault()) {}
                FootballFixtureRow(pending, ZoneId.systemDefault()) {}
            }
        } }
        rule.onNodeWithText("终止").assertExists()
        rule.onNodeWithTag("reminder-${stopped.id}").assertDoesNotExist()
        rule.onNodeWithTag("reminder-${pending.id}").assertExists()
        // Both fixtures stay in the scrollable list, in provider order, with their recorded score.
        rule.onNodeWithTag("match-${stopped.id}").assertExists()
        rule.onNodeWithTag("match-${pending.id}").assertExists()
    }

    @Test fun archivedStoppedLaLigaFixtureKeepsProviderStateWithoutAnInventedScore() {
        val league = footballCompetitions.first { it.id == "esp.1" }
        val url = EspnFootballAdapter.scheduleUrl(league.id, 2026)
        val matches = requireNotNull(SportsArchive(context).read(url) { EspnFootballAdapter.matches(it, league, 2026) }) {
            "Missing archived La Liga scoreboard: $url"
        }.data
        val stopped = matches.first { it.date == "2026-10-10" && it.home.contains("Rayo") }
        // The packaged response records the provider's SUSPENDED state; it must never parse as "待赛".
        assertEquals("中止", stopped.status)
        assertEquals("— : —", stopped.score)
        assertTrue(matches.any { it.status == "待赛" })
        assertTrue(matches.any { it.status == "完场" })
    }
}
