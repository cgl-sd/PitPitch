package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.EspnFootballAdapter
import com.example.pitpitch.data.PENDING_TEAM_NAME
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.data.sampleMatches
import com.example.pitpitch.feature.FootballFixtureRow
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** A published slot without a confirmed participant must never render as a blank club. */
@RunWith(AndroidJUnit4::class)
class PendingParticipantTest {
    @get:Rule val rule = createComposeRule()
    private val league = footballCompetitions.first { it.id == "uefa.champions" }

    private fun fixture(homeName: String, awayName: String, homeShort: String, awayShort: String) = """
        {"events":[{"id":"401999999","date":"2027-04-07T19:00Z","season":{"year":2026},
        "competitions":[{"altGameNote":"UEFA Champions League, Quarterfinals","timeValid":true,
        "venue":{"fullName":"TBD"},"status":{"clock":0,"displayClock":"0'","period":0,
        "type":{"name":"STATUS_SCHEDULED","state":"pre","completed":false,"detail":"Scheduled"}},
        "competitors":[
        {"homeAway":"home","score":"0","team":{"id":"1","displayName":"$homeName","shortDisplayName":"$homeShort"}},
        {"homeAway":"away","score":"0","team":{"id":"2","displayName":"$awayName","shortDisplayName":"$awayShort"}}]}]}]}
    """.trimIndent()

    @Test fun providerSlotWithoutTeamsBecomesPendingInsteadOfBlank() {
        val matches = EspnFootballAdapter.matches(fixture("", "", "", ""), league, 2026)
        val match = matches.single()
        assertEquals(PENDING_TEAM_NAME, match.home)
        assertEquals(PENDING_TEAM_NAME, match.away)
        assertEquals("— : —", match.score)

        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            FootballFixtureRow(match, ZoneId.systemDefault()) {}
        } }
        // Both sides of the published slot render the placeholder, so no club cell is ever blank.
        rule.onAllNodesWithText(PENDING_TEAM_NAME, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun confirmedTeamsAndShortsSurviveUnchanged() {
        val matches = EspnFootballAdapter.matches(fixture("Real Madrid", "Bayern Munich", "Real Madrid", "Bayern"), league, 2026)
        val match = matches.single()
        assertEquals("Real Madrid", match.home)
        assertEquals("Bayern Munich", match.away)
        assertFalse(match.home.isBlank())
    }
}
