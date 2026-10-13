package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.local.UserStore
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.Year

class HomeFootballConsistencyTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val zone get() = ZoneId.systemDefault()
    private fun repository(): EuropeanFootballRepository {
        val db = PitPitchDatabase.build(rule.activity)
        val archive = SportsArchive(rule.activity, db)
        return EuropeanFootballRepository(JsonClient(archive = archive, database = db), FootballStore(db), archive)
    }
    private fun capture(name: String) {
        val file = File(rule.activity.cacheDir, "pitpitch-validation/consistency-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun texts(id: String): List<String> = rule.onNodeWithTag("match-$id").fetchSemanticsNode()
        .config[SemanticsProperties.Text].map { it.text }

    @Test fun allSevenEnabledCompetitionsShowIdenticalFixturesAcrossInstalledPages() {
        rule.waitUntil(40000) { rule.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty() }
        val store = UserStore(rule.activity)
        val before = runBlocking { store.settings.first() }
        val ids = footballCompetitions.map { it.id } + "f1"
        val year = Year.now().value
        try {
            runBlocking { footballCompetitions.forEach { store.homeCompetition(it.id, true) } }
            rule.onNodeWithTag("nav-1").performClick()
            rule.chooseScheduleSeason("football-season", year)
            rule.onNodeWithTag("nav-0").performClick()
            val response = runBlocking { repository().season(year) }
            val rounds = homeFootballRounds(response.matches, zone, Instant.now())
            assertEquals(footballCompetitions.map { it.id }.toSet(), rounds.map { it.matches.first().competitionId }.toSet())
            val previews = mutableMapOf<String, List<String>>()
            for (league in footballCompetitions) {
                val round = rounds.single { it.matches.first().competitionId == league.id }
                rule.scrollToScheduleTag("home-round-${round.key}")
                rule.onAllNodesWithTag("home-round-${round.key}").assertCountEquals(1)
                for (match in round.matches.take(3)) {
                    rule.scrollToScheduleTag("match-${match.id}")
                    rule.onNodeWithTag("match-${match.id}").assertIsDisplayed()
                    previews[match.id] = texts(match.id)
                }
                capture("home-${league.id}")
            }
            rule.onNodeWithTag("nav-1").performClick()
            rule.onNodeWithTag("football-section-0").performClick()
            for (league in footballCompetitions) {
                rule.onNodeWithText(league.title, useUnmergedTree = true).performScrollTo().performClick()
                rule.waitForIdle()
                val round = rounds.single { it.matches.first().competitionId == league.id }
                // This page was deliberately scrolled to index 0 by season selection above.
                // Position the real home round explicitly; revisiting an unchanged context must keep manual position.
                lateinit var selected: com.example.pitpitch.app.SportsViewModel.State
                rule.runOnUiThread {
                    selected = androidx.lifecycle.ViewModelProvider(rule.activity)[com.example.pitpitch.app.SportsViewModel::class.java].state.value
                }
                val displayed = selected.matches.filter { it.competitionId == league.id && (!league.european || isEuropeanGroupStage(it.stage)) }
                    .sortedBy { homeMatchTime(it, zone) }
                val firstInRound = displayed.indexOfFirst { footballRoundKey(it) == round.stage }
                assertTrue(firstInRound >= 0)
                rule.onNode(hasScrollToIndexAction()).performScrollToIndex(firstInRound + if (selected.footballError != null) 1 else 0)
                rule.onNodeWithTag("football-round").assertTextContains(footballRoundNavigationLabel(round.stage))
                for (match in round.matches.take(3)) {
                    rule.scrollToScheduleTag("match-${match.id}")
                    val footballText = texts(match.id)
                    if (previews[match.id] != footballText) {
                        // A genuine live update can arrive between visits. Revisit home and require the same updated record.
                        rule.onNodeWithTag("nav-0").performClick()
                        rule.scrollToScheduleTag("match-${match.id}")
                        assertEquals("${league.title} ${match.id}: refreshed home must match football", footballText, texts(match.id))
                        capture("refreshed-home-${league.id}")
                        rule.onNodeWithTag("nav-1").performClick()
                        rule.scrollToScheduleTag("match-${match.id}")
                        assertEquals(footballText, texts(match.id))
                    } else assertEquals("${league.title} ${match.id}: name/time/status/score", previews[match.id], footballText)
                }
                capture("football-${league.id}")
                val phaseMatches = response.matches.filter { it.competitionId == league.id && (!league.european || isEuropeanGroupStage(it.stage)) }
                val keys = footballStages(phaseMatches)
                val index = keys.indexOf(round.stage)
                if (index < keys.lastIndex) {
                    rule.onNodeWithTag("football-next-round").performClick()
                    rule.onNodeWithTag("football-round").assertTextContains(footballRoundNavigationLabel(keys[index + 1]))
                    rule.onNodeWithTag("football-prev-round").performClick()
                    rule.onNodeWithTag("football-round").assertTextContains(footballRoundNavigationLabel(round.stage))
                }
                rule.onNodeWithTag("football-section-1").performClick()
                rule.waitForIdle()
                if (response.tables[league.id]?.data.orEmpty().isEmpty()) rule.onNodeWithText("暂无联赛积分").assertExists()
                else rule.onNodeWithText("$year/${year + 1} 赛季积分").assertExists()
                capture("points-${league.id}")
                rule.onNodeWithTag("football-section-0").performClick()
            }
        } finally {
            runBlocking { ids.forEach { store.homeCompetition(it, it !in before.hiddenHomeCompetitions) } }
            assertEquals(before.hiddenHomeCompetitions, runBlocking { store.settings.first().hiddenHomeCompetitions })
        }
    }

    @Test fun all49CompetitionSeasonsKeepSourceFixturesAndHistoricalNavigation() {
        rule.waitUntil(40000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
        val repo = repository()
        val archive = SportsArchive(rule.activity)
        for (year in 2020..2026) {
            val response = runBlocking { repo.season(year) }
            for (league in footballCompetitions) {
                val matches = response.matches.filter { it.competitionId == league.id }
                assertTrue("${league.title} $year has archived fixtures", matches.isNotEmpty())
                assertEquals(matches.size, matches.map { it.id }.distinct().size)
                val original = listOf(year, year + 1).flatMap { calendar ->
                    archive.read(EspnFootballAdapter.scheduleUrl(league.id, calendar)) { EspnFootballAdapter.matches(it, league, year) }!!.data
                }.distinctBy { it.id }
                // A newer valid database response may replace an archive; shared display selection must never alter its facts.
                val clock = matches.map { homeMatchTime(it, zone) }.filter { it != Instant.MAX }.minOrNull()!!.minusSeconds(1)
                homeFootballRounds(matches, zone, clock).forEach { round ->
                    assertTrue(round.matches.size > 0)
                    assertTrue(round.matches.all { it in matches && footballRoundKey(it) == round.stage })
                }
                assertTrue("${league.title} $year source archive", original.isNotEmpty())
                if (year < 2026) assertTrue("${league.title} $year historical home must not show past fixtures",
                    homeFootballRounds(matches, zone, Instant.now()).isEmpty())
                val file = File(rule.activity.cacheDir, "pitpitch-validation/consistency-data.txt")
                file.appendText("${league.id} $year database=${matches.size} archive=${original.size} rounds=${footballStages(matches).size}\n")
            }
        }
        rule.onNodeWithTag("nav-1").performClick()
        rule.chooseScheduleSeason("football-season", 2020)
        rule.onNodeWithTag("football-section-0").performClick()
        val matches = runBlocking { repo.season(2020) }.matches
        for (league in footballCompetitions) {
            rule.onNodeWithText(league.title, useUnmergedTree = true).performScrollTo().performClick()
            rule.waitForIdle()
            val phaseMatches = matches.filter { it.competitionId == league.id && (!league.european || isEuropeanGroupStage(it.stage)) }
            val key = nextFootballMatch(phaseMatches, Instant.now(), zone)?.let(::footballRoundKey) ?: footballStages(phaseMatches).last()
            rule.onNodeWithTag("football-round").assertTextContains(footballRoundNavigationLabel(key))
            rule.onNodeWithTag("football-round").performClick()
            val first = phaseMatches.filter { footballRoundKey(it) == key }.minBy { homeMatchTime(it, zone) }
            try {
                rule.waitUntil(5000) { runCatching { rule.onNodeWithTag("match-${first.id}").assertIsDisplayed(); true }.getOrDefault(false) }
                rule.onNodeWithTag("match-${first.id}").assertIsDisplayed()
            } catch (error: Throwable) {
                capture("failed-history-${league.id}")
                File(rule.activity.cacheDir, "pitpitch-validation/consistency-failed.txt").writeText(
                    "${league.id} expected ${first.id} ${first.kickoffUtc} ${first.date} ${first.status}\n" +
                        rule.onRoot().printToString())
                throw error
            }
            capture("history-2020-${league.id}")
            if (league.european) {
                rule.onNodeWithText("淘汰赛", useUnmergedTree = true).performClick()
                rule.waitForIdle()
                // Knockouts have no round navigation row: the bracket is one continuous list.
                rule.onNodeWithTag("football-round").assertDoesNotExist()
                capture("history-knockout-${league.id}")
            }
        }
        rule.chooseScheduleSeason("football-season", Year.now().value)
    }
}
