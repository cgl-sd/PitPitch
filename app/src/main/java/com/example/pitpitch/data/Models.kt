package com.example.pitpitch.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FootballMatch(
    val id: String,
    val competition: String,
    val stage: String,
    val home: String,
    val away: String,
    val homeShort: String,
    val awayShort: String,
    val score: String,
    val status: String,
    val stadium: String,
    val date: String = "2026-10-10",
    val kickoffUtc: String? = null,
    val lineupAvailable: Boolean = true,
    val eventsAvailable: Boolean = true,
    val statsAvailable: Boolean = true,
    val isDemo: Boolean = true,
    val source: String = "本地演示样例",
    val sourceUpdated: String? = null,
    val events: List<MatchEvent> = emptyList(),
    val homeTeamId: Int? = null,
    val awayTeamId: Int? = null,
    val homeBadgeUrl: String? = null,
    val awayBadgeUrl: String? = null,
    val competitionId: String? = null, val leg: Int? = null,
    val roundNumber: Int? = null, val roundSource: String? = null,

    val liveDetail: String? = null,
)

/** UI prototypes only. Never imply real fixtures, confirmed lineups, or live scores. */
val sampleMatches = listOf(
    FootballMatch("fb-demo-1", "演示 · 英格兰联赛", "第 8 轮", "蓝城 FC", "红联 FC", "蓝城", "红联", "2 : 1", "演示完场", "城市体育场"),
    FootballMatch("fb-demo-2", "演示 · 欧洲赛事", "联赛阶段第 3 轮", "北伦敦队", "米兰蓝黑队", "北伦敦", "蓝黑", "— : —", "演示待赛", "新球场"),
    FootballMatch("fb-demo-3", "演示 · 西班牙联赛", "第 9 轮", "马德里白队", "巴塞罗那队", "马德里", "巴萨", "1 : 1", "演示进行中", "首都体育场"),
)

data class F1Session(val title: String, val date: String, val time: String?) {
    fun localTime(zone: ZoneId = ZoneId.systemDefault()): String = formatRaceTime(date, time, zone)
}

data class F1Race(
    val season: Int,
    val round: Int,
    val name: String,
    val circuit: String,
    val date: String,
    val utcTime: String?,
    val country: String,
    val sessions: List<F1Session> = emptyList(),
    val circuitId: String? = null,
) {
    val id: String get() = "f1-$season-$round"

    fun localTime(zone: ZoneId = ZoneId.systemDefault()): String = formatRaceTime(date, utcTime, zone)

    /** Prefer exact UTC start where available, so today's finished races aren't upcoming. */
    fun isUpcoming(): Boolean = runCatching {
        if (!utcTime.isNullOrBlank()) {
            Instant.parse("${date}T$utcTime").isAfter(Instant.now())
        } else {
            !LocalDate.parse(date).isBefore(LocalDate.now())
        }
    }.getOrDefault(false)

    fun daysUntil(): Long? = runCatching {
        if (utcTime.isNullOrBlank()) return@runCatching null
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), Instant.parse("${date}T$utcTime").atZone(ZoneId.systemDefault()).toLocalDate())
    }.getOrNull()
}

fun formatRaceTime(date: String, utcTime: String?, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
    if (utcTime.isNullOrBlank()) {
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MM月dd日 EEE", Locale.CHINA)) + " · 时间待定"
    } else {
        Instant.parse("${date}T$utcTime")
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("MM月dd日 EEE HH:mm", Locale.CHINA))
    }
}.getOrElse { date }

/** Added time sorts after regular time in the same minute. */
data class MatchEvent(val minute: Int, val added: Int = 0, val label: String) {
    val clock: String get() = if (added > 0) "$minute+$added′" else "$minute′"
}
fun demoEvents(match: FootballMatch): List<MatchEvent> {
    if (!match.eventsAvailable || match.status.contains("延期") || match.status.contains("取消")) return emptyList()
    val events = listOf(
        MatchEvent(12, label = "${match.home} 进球"),
        MatchEvent(28, label = "${match.away} 黄牌"),
        MatchEvent(45, 2, "${match.home} 黄牌"),
        MatchEvent(51, label = "${match.home} 进球"),
        MatchEvent(61, label = "${match.home} 换人"),
        MatchEvent(76, label = "${match.away} 进球"),
        MatchEvent(90, 3, "比赛结束"),
    )
    return events.filter { !match.status.contains("进行") || (it.minute <= 76 && it.minute != 51) }
        .sortedWith(compareBy({ it.minute }, { it.added }))
}

data class FootballTeam(val id: Int, val name: String, val badgeUrl: String?)

/** Suppliers may omit seconds, but downstream clocks use canonical UTC instants. */
fun parseProviderInstant(value: String): String? = runCatching { java.time.OffsetDateTime.parse(value).toInstant().toString() }.getOrNull()
