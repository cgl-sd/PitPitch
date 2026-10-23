package com.example.pitpitch.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** A venue shown as a home-fixture venue, never inferred as permanent club metadata. */
fun teamHomeFixtureVenue(matches: List<FootballMatch>, teamId: Int, league: String): String? = matches
    .filter { !it.isDemo && it.homeTeamId == teamId && it.competitionId == league }
    .map { it.stadium }.filter { it.isNotBlank() && it != "场地未提供" }.distinct().singleOrNull()

fun teamPublishedStanding(tables: List<FootballTable>, teamId: Int): FootballStanding? = tables
    .flatMap { it.entries }.filter { it.team.id == teamId }.singleOrNull()

fun teamProfileMatches(matches: List<FootballMatch>, teamId: Int, competition: String?, side: Int): List<FootballMatch> = matches.filter {
    (it.homeTeamId == teamId || it.awayTeamId == teamId) && (competition == null || it.competitionId == competition) &&
        when (side) { 1 -> it.homeTeamId == teamId; 2 -> it.awayTeamId == teamId; else -> true }
}
fun teamFixtureIsUpcoming(match: FootballMatch, now: Instant, zone: ZoneId): Boolean =
    !match.status.contains("完场") && !match.status.contains("取消") && !match.status.contains("延期") &&
        (homeMatchTime(match, zone) >= now || (match.status.contains("进行中") && matchDay(match, zone) == now.atZone(zone).toLocalDate()))

fun teamFixtureMonth(match: FootballMatch, zone: ZoneId): YearMonth? = matchDay(match, zone)?.let(YearMonth::from)
