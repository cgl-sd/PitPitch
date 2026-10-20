package com.example.pitpitch

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.EuropeanFootballRepository
import com.example.pitpitch.feature.TeamProfileScreen
import com.example.pitpitch.ui.SportsColors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.ZoneId

class TeamProfilePresentationTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun season(year: Int): FootballArchiveSeason = runBlocking {
        val archive = SportsArchive(context)
        EuropeanFootballRepository(JsonClient(archive = archive), archive).season(year)
    }
    @org.junit.Before fun prepare() = runBlocking { com.example.pitpitch.data.repository.localBackend(context).prepare() }
    private fun capture(name: String) {
        val file = File(context.cacheDir, "pitpitch-validation/team-redesign-$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun delayedScheduleOpensAtUpcomingAndPreservesManualPositionAcrossRefreshAndTabs() {
        val response = season(2026)
        val teamId = 1000360
        val fixture = response.matches.first { it.homeTeamId == teamId }
        val team = FootballTeam(teamId, fixture.home, fixture.homeBadgeUrl)
        val zone = ZoneId.systemDefault()
        val fixtures = teamProfileMatches(response.matches, teamId, null, 0).sortedBy { homeMatchTime(it, zone) }
        val next = nextFootballMatch(fixtures, java.time.Instant.now(), zone)!!
        val shown = mutableStateOf(SportsViewModel.State(footballSeason = 2026, footballLoading = true))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            TeamProfileScreen(shown.value, team, 2026, "eng.1", zone, {}, {})
        } }
        rule.runOnIdle { shown.value = shown.value.copy(matches = response.matches, footballLoading = false) }
        rule.onNodeWithTag("match-${next.id}").assertIsDisplayed()
        rule.onNodeWithTag("team-profile").assertIsDisplayed()
        rule.onNodeWithTag("team-profile-section-0").assertIsDisplayed()
        capture("upcoming-initial")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("match-${fixtures.first().id}"))
        rule.runOnIdle { shown.value = shown.value.copy(footballLoading = true) }
        rule.onNodeWithTag("match-${fixtures.first().id}").assertIsDisplayed()
        rule.onNodeWithTag("team-profile-section-1").performClick()
        rule.onNodeWithTag("team-profile-section-0").performClick()
        rule.onNodeWithTag("match-${fixtures.first().id}").assertIsDisplayed()
    }
    @Test fun brightonOverviewUsesRealIdentityStatisticsAndLargerOriginalBadge() {
        val response = season(2026)
        val fixture = response.matches.first { it.homeTeamId == 1000331 }
        val team = FootballTeam(1000331, fixture.home, fixture.homeBadgeUrl)
        val state = SportsViewModel.State(footballSeason = 2026, matches = response.matches, footballTables = response.tables)
        val standing = teamPublishedStanding(response.tables["eng.1"]!!.data, team.id)!!
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            TeamProfileScreen(state, team, 2026, "eng.1", ZoneId.systemDefault(), {}, {})
        } }
        rule.onNode(hasText("布莱顿") and hasAnyAncestor(hasTestTag("team-profile"))).assertExists()
        rule.onNodeWithText("Brighton & Hove Albion").assertExists()
        rule.onNodeWithTag("team-profile-position").assertTextEquals(standing.position)
        rule.onNodeWithTag("team-profile-points").assertDoesNotExist()
        rule.onNodeWithTag("team-profile-played").assertDoesNotExist()
        rule.onNodeWithText("2026/2027 赛季 · 英超").assertExists()
        rule.onAllNodes(hasText("总身价", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasText("关注", substring = true)).assertCountEquals(0)
        val hero = rule.onNodeWithTag("team-profile").captureToImage().toPixelMap()
        assertTrue("Brighton hero uses the blue provider primary color", hero[4, hero.height / 2].blue > .5f && hero[4, hero.height / 2].red < .2f)
        capture("brighton-overview")
        rule.onNodeWithText("后续赛程").assertDoesNotExist()
        rule.onNodeWithText("历史赛程").assertDoesNotExist()
        val seasonFixtures = response.matches.filter { it.homeTeamId == team.id || it.awayTeamId == team.id }
        for (fixture in listOf(seasonFixtures.first { it.status.contains("完场") }, seasonFixtures.first { it.status.contains("待赛") })) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("match-${fixture.id}"))
            rule.onNodeWithTag("match-${fixture.id}").assertExists()
            capture(if (fixture.status.contains("完场")) "season-completed" else "season-upcoming")
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithTag("team-profile-side").assertDoesNotExist()
        for (fixture in listOf(response.matches.first { it.homeTeamId == team.id }, response.matches.first { it.awayTeamId == team.id })) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("match-${fixture.id}"))
            rule.onNodeWithTag("match-${fixture.id}").assertExists()
        }
    }
    @Test fun competitionFiltersMonthlyFixturesAndPlayersKeepTheirRouteScope() {
        val response = season(2026)
        val teamId = 1000360
        val fixture = response.matches.first { it.homeTeamId == teamId }
        val team = FootballTeam(teamId, fixture.home, fixture.homeBadgeUrl)
        val archive = SportsArchive(context)
        val squad = runBlocking { EuropeanFootballRepository(JsonClient(archive = archive), archive).squad(2026, "eng.1", teamId, false) }
        val state = SportsViewModel.State(footballSeason = 2026, matches = response.matches, footballTables = response.tables,
            squads = mapOf("2026|eng.1|$teamId" to squad))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            TeamProfileScreen(state, team, 2026, "eng.1", ZoneId.systemDefault(), {}, {})
        } }
        rule.onNodeWithText("已收录赛事").assertDoesNotExist()
        rule.onNodeWithText("国内杯赛赛程暂未提供").assertDoesNotExist()
        val allCompetitions = hasText("全部赛事") and hasAnyAncestor(hasTestTag("team-profile-competition"))
        val championsLeague = hasText("欧冠") and hasAnyAncestor(hasTestTag("team-profile-competition"))
        rule.onNode(allCompetitions).assertIsSelected()
        rule.onNode(championsLeague).performClick().assertIsSelected()
        val euro = response.matches.first { it.competitionId == "uefa.champions" && (it.homeTeamId == teamId || it.awayTeamId == teamId) }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("match-${euro.id}"))
        rule.onNodeWithTag("match-${euro.id}").assertExists()
        capture("europe-months")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNode(championsLeague).performClick().assertIsSelected()
        rule.onNode(allCompetitions).performClick().assertIsSelected()
        val leagueMatch = response.matches.first { it.competitionId == "eng.1" && (it.homeTeamId == teamId || it.awayTeamId == teamId) }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("match-${leagueMatch.id}"))
        rule.onNodeWithTag("match-${leagueMatch.id}").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithTag("team-profile-section-1").performClick()
        for (position in listOf("前锋", "中场", "后卫", "门将")) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("team-position-$position"))
            rule.onNodeWithTag("team-position-$position").assertExists()
        }
        assertEquals("eng.1", state.footballCompetition)
        assertEquals(response.matches, state.matches)
    }
    @Test fun refreshedTeamScheduleReplacesFixturesAndMonthHeadersWithoutChangingSourceOrder() {
        val response = season(2026)
        val fixtures = response.matches.filter { it.competitionId == "eng.1" && it.homeTeamId == 1000360 && it.kickoffUtc != null }
            .sortedBy { java.time.Instant.parse(it.kickoffUtc) }
        assertTrue(fixtures.size >= 6)
        val team = FootballTeam(1000360, fixtures.first().home, fixtures.first().homeBadgeUrl)
        val shown = mutableStateOf(SportsViewModel.State(footballSeason = 2026, matches = fixtures.take(3)))
        val zone = ZoneId.of("UTC")
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            TeamProfileScreen(shown.value, team, 2026, "eng.1", zone, {}, {})
        } }
        for (batch in listOf(fixtures.take(3), fixtures.drop(3).take(3))) {
            rule.runOnIdle { shown.value = shown.value.copy(matches = batch) }
            var previousMonth: java.time.YearMonth? = null
            for ((index, fixture) in batch.withIndex()) {
                val month = java.time.YearMonth.from(java.time.Instant.parse(fixture.kickoffUtc).atZone(zone))
                if (index == 0 || month != previousMonth) {
                    val monthTag = "team-month-season-$index"
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(monthTag))
                    rule.onNodeWithTag(monthTag).assertTextEquals("${month.year}年${month.monthValue}月")
                }
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("match-${fixture.id}"))
                rule.onNodeWithTag("match-${fixture.id}").assertIsDisplayed()
                previousMonth = month
            }
        }
        assertEquals(fixtures.drop(3).take(3), shown.value.matches)
        for (removed in fixtures.take(3)) rule.onNodeWithTag("match-${removed.id}").assertDoesNotExist()
        assertEquals(response.matches.filter { it.competitionId == "eng.1" && it.homeTeamId == team.id && it.kickoffUtc != null }
            .sortedBy { java.time.Instant.parse(it.kickoffUtc) }, fixtures)
    }
    @Test fun wrongSeasonKeepsOverviewUnknownInsteadOfBorrowingOldPointsOrVenue() {
        val response = season(2025)
        val fixture = response.matches.first { it.homeTeamId == 1000331 }
        val team = FootballTeam(1000331, fixture.home, fixture.homeBadgeUrl)
        val state = SportsViewModel.State(footballSeason = 2025, matches = response.matches, footballTables = response.tables)
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            TeamProfileScreen(state, team, 2026, "eng.1", ZoneId.systemDefault(), {}, {})
        } }
        rule.onNodeWithTag("team-profile-position").assertTextEquals("—")
        rule.onNodeWithTag("team-profile-points").assertDoesNotExist()
        rule.onNodeWithTag("team-profile-played").assertDoesNotExist()
        rule.onNodeWithTag("team-profile-venue").assertDoesNotExist()
        rule.onNodeWithText("暂无球队赛程").assertExists()
        capture("missing-season")
    }
}
