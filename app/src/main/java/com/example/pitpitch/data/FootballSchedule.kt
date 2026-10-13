package com.example.pitpitch.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun footballStageLabel(stage: String): String = Regex("^(\\d+)\\.\\s*Spieltag$").matchEntire(stage)?.let { "第 ${it.groupValues[1]} 轮" } ?: stage
fun footballStages(matches: List<FootballMatch>): List<String> = matches.map(::footballRoundKey).distinct()
    .sortedWith(compareBy({ Regex("\\d+").find(it)?.value?.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
fun nextFootballMatch(matches: List<FootballMatch>, now: Instant, zone: ZoneId): FootballMatch? =
    matches.filter { match ->
        match.status.contains("待赛") && !match.status.contains("取消") && !match.status.contains("延期") &&
            (match.kickoffUtc?.let { runCatching { Instant.parse(it).isAfter(now) }.getOrDefault(false) }
                ?: (matchDay(match, zone)?.let { !it.isBefore(now.atZone(zone).toLocalDate()) } == true))
    }.minByOrNull { homeMatchTime(it, zone) }

fun footballRoundNavigationLabel(stage: String): String =
    if (stage.contains("轮次未提供")) "轮次未提供" else footballStageLabel(stage)
