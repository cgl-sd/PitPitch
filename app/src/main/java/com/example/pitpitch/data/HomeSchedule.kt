package com.example.pitpitch.data

import java.time.*
import java.time.temporal.TemporalAdjusters

data class HomeFootballRound(val competition: String, val stage: String, val matches: List<FootballMatch>, val preview: Boolean = false) {
    val key get() = "$competition|$stage"
    val title get() = listOf(if (competition.startsWith("德国") && competition.contains("1.") && competition.contains("Bundesliga")) "德甲" else competition,
        Regex("^(\\d+)\\.\\s*Spieltag$").matchEntire(stage)?.let { "第 ${it.groupValues[1]} 轮" } ?: stage)
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/** Display labels only; provider names and stable race identifiers remain untouched. */
fun homeRaceName(name: String): String {
    val countries = mapOf("Australian" to "澳大利亚", "Chinese" to "中国", "Japanese" to "日本", "Bahrain" to "巴林",
        "Saudi Arabian" to "沙特阿拉伯", "Miami" to "迈阿密", "Canadian" to "加拿大", "Monaco" to "摩纳哥",
        "Spanish" to "西班牙", "Austrian" to "奥地利", "British" to "英国", "Belgian" to "比利时", "Hungarian" to "匈牙利",
        "Dutch" to "荷兰", "Italian" to "意大利", "Azerbaijan" to "阿塞拜疆", "Singapore" to "新加坡",
        "United States" to "美国", "Mexico City" to "墨西哥城", "São Paulo" to "圣保罗", "Las Vegas" to "拉斯维加斯",
        "Qatar" to "卡塔尔", "Abu Dhabi" to "阿布扎比", "Emilia Romagna" to "艾米利亚罗马涅")
    return countries[name.removeSuffix(" Grand Prix")]?.let { "${it}大奖赛" } ?: name
}

fun weekStart(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun matchDay(match: FootballMatch, zone: ZoneId): LocalDate? = runCatching {
    match.kickoffUtc?.let { Instant.parse(it).atZone(zone).toLocalDate() } ?: LocalDate.parse(match.date)
}.getOrNull()

/** One provider round per competition; an elapsed final kickoff closes the whole round. */
fun homeFootballRounds(matches: List<FootballMatch>, zone: ZoneId, now: Instant): List<HomeFootballRound> {
    val today = now.atZone(zone).toLocalDate()
    return matches.groupBy { it.competitionId ?: it.competition }.values.mapNotNull { competitionMatches ->
        val groups = competitionMatches.groupBy(::footballRoundKey).values.map { games ->
            games.filter { !it.status.contains("取消") && !it.status.contains("延期") && matchDay(it, zone) != null }
                .sortedBy { homeMatchTime(it, zone) }
        }.filter { it.isNotEmpty() }
        fun end(games: List<FootballMatch>): Instant? {
            val times = games.map { it.kickoffUtc?.let { utc -> runCatching { Instant.parse(utc) }.getOrNull() } }
            return if (times.any { it == null }) null else times.filterNotNull().maxOrNull()
        }
        // Older makeup fixtures must not reappear after a later numbered round has closed.
        val closedThrough = groups.filter { end(it)?.let { cutoff -> now.isAfter(cutoff) } == true }
            .mapNotNull { it.first().roundNumber }.maxOrNull()
        val candidates = groups.mapNotNull candidate@ { games ->
            val number = games.first().roundNumber
            if (number != null && closedThrough != null && number <= closedThrough) return@candidate null
            val cutoff = end(games)
            if (cutoff != null && now.isAfter(cutoff)) return@candidate null
            val remaining = games.filter { match ->
                if (match.status.contains("完场")) false
                else {
                    val kickoff = match.kickoffUtc?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    if (kickoff == null) matchDay(match, zone)!! >= today
                    else !kickoff.isBefore(now) || (match.status.contains("进行中") && matchDay(match, zone) == today)
                }
            }
            if (remaining.isEmpty()) null else HomeFootballRound(games.first().competition, footballRoundKey(games.first()), remaining,
                preview = homeMatchTime(games.first(), zone).isAfter(now))
        }
        // Round numbers come from provider metadata, never from dates or list positions.
        candidates.sortedWith(compareBy<HomeFootballRound> { it.matches.first().roundNumber ?: Int.MAX_VALUE }
            .thenBy { homeMatchTime(it.matches.first(), zone) }).firstOrNull()
    }.sortedBy { homeMatchTime(it.matches.first(), zone) }
}

fun sessionDay(session: F1Session, zone: ZoneId): LocalDate? = runCatching {
    if (session.time.isNullOrBlank()) LocalDate.parse(session.date)
    else Instant.parse("${session.date}T${session.time}").atZone(zone).toLocalDate()
}.getOrNull()
fun homeMatchTime(match: FootballMatch, zone: ZoneId): Instant =
    match.kickoffUtc?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: matchDay(match, zone)?.atTime(LocalTime.MAX)?.atZone(zone)?.toInstant() ?: Instant.MAX

fun homeSessionTime(session: F1Session, zone: ZoneId): Instant = runCatching {
    if (session.time.isNullOrBlank()) LocalDate.parse(session.date).atTime(LocalTime.MAX).atZone(zone).toInstant()
    else Instant.parse("${session.date}T${session.time}")
}.getOrDefault(Instant.MAX)

fun homeRaceTime(race: F1Race, zone: ZoneId, now: Instant): Instant =
    race.displaySessions(includeRace = true, homePreview = true).map { homeSessionTime(it, zone) }
        .filter { it.isAfter(now) }.minOrNull() ?: Instant.MAX

/** One race at a time, advancing by calendar time without a natural-week boundary. */
fun homeRace(races: List<F1Race>, zone: ZoneId, now: Instant): F1Race? = races
    .filter { homeSessionTime(F1Session("正赛", it.date, it.utcTime), zone).isAfter(now) }
    .minByOrNull { homeSessionTime(F1Session("正赛", it.date, it.utcTime), zone) }
