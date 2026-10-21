package com.example.pitpitch.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun currentFootballYear(): Int = java.time.LocalDate.now().let { if (it.monthValue < 7) it.year - 1 else it.year }

/** Refresh the next preview round and recover unresolved results that have aged out of the preview. */
fun homeRefreshMatches(matches: List<FootballMatch>, hidden: Set<String>, now: Instant, zone: ZoneId): List<FootballMatch> {
    val visible = matches.filter { it.competitionId !in hidden }
    val next = homeFootballRounds(visible, zone, now).flatMap { it.matches }
    val unresolved = visible.filter { match ->
        val open = match.status in setOf("待赛", "进行中", "中止")
        val kickoff = match.kickoffUtc?.let { parseProviderInstant(it)?.let(Instant::parse) }
        open && if (kickoff != null) !kickoff.isAfter(now)
            else matchDay(match, zone)?.let { it <= now.atZone(zone).toLocalDate() } == true
    }
    return (unresolved + next).distinctBy { it.id }.sortedBy { homeMatchTime(it, zone) }
}

fun homeRefreshDays(matches: List<FootballMatch>): List<String> = matches.mapNotNull { match ->
    (match.kickoffUtc?.let { runCatching { Instant.parse(it).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull() }
        ?: runCatching { java.time.LocalDate.parse(match.date) }.getOrNull())
        ?.format(DateTimeFormatter.BASIC_ISO_DATE)
}.distinct().sorted()

/** Single taps navigate immediately; only a second nearby tap requests a refresh. */
class HomeRefreshTaps(private val intervalMillis: Long = 350) {
    private var previous: Long? = null
    fun tap(nowMillis: Long): Boolean {
        val last = previous
        val twice = last != null && nowMillis - last in 0..intervalMillis
        previous = if (twice) null else nowMillis
        return twice
    }
    fun reset() { previous = null }
}
