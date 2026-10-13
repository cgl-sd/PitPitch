package com.example.pitpitch.data

/** Supplementary metadata only: the original ESPN stage and fixture fields remain intact. */
data class FootballRoundInfo(val matchId: String, val competitionId: String, val season: Int,
    val homeTeamId: Int, val awayTeamId: Int, val providerNote: String, val number: Int, val source: String)

fun applyFootballRound(match: FootballMatch, season: Int, info: FootballRoundInfo?): FootballMatch {
    if (info == null || match.isDemo || match.id != info.matchId || match.competitionId != info.competitionId ||
        season != info.season || match.homeTeamId != info.homeTeamId || match.awayTeamId != info.awayTeamId ||
        info.number <= 0 || !info.source.startsWith("https://")) return match
    val european = info.competitionId.startsWith("uefa.")
    if (european && (!isEuropeanGroupStage(match.stage) || europeanStage(info.providerNote) != match.stage ||
            info.number > if (season >= 2024) 8 else 6)) return match
    val maximum = if (info.competitionId == "ger.1" || (info.competitionId == "fra.1" && season >= 2023)) 34 else 38
    if (!european && (match.stage != "常规联赛 · 轮次未提供" || info.number > maximum)) return match
    return match.copy(roundNumber = info.number, roundSource = info.source)
}

/** All historical groups share a matchday; each fixture still retains its group label. */
fun footballRoundKey(match: FootballMatch): String = match.roundNumber?.let { number ->
    when {
        isEuropeanGroupStage(match.stage) -> "${if (match.stage.startsWith("小组 ")) "小组赛" else "联赛阶段"} · 第 $number 轮"
        match.stage.contains("轮次未提供") -> "第 $number 轮"
        else -> match.stage
    }
} ?: match.stage

fun footballMatchStageLabel(match: FootballMatch): String =
    if (match.roundNumber != null && match.stage.startsWith("小组 ")) "${match.stage} · 第 ${match.roundNumber} 轮"
    else footballRoundKey(match)
