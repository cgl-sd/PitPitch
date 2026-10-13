package com.example.pitpitch.data

/** Pure, UI-independent filters; backend-provided matches can use the same logic later. */
fun filterMatches(
    matches: List<FootballMatch>,
    league: String,
    statusIndex: Int,
    keyword: String,
    date: String? = null,
): List<FootballMatch> {
    val normalizedKeyword = keyword.trim()
    return matches.filter { match ->
        (date == null || match.date == date) &&
        (league == "全部" || match.competition.contains(league)) &&
            when (statusIndex) {
                1 -> match.status.contains("进行")
                2 -> match.status.contains("完场")
                3 -> match.status.contains("待赛")
                else -> true
            } &&
            (normalizedKeyword.isBlank() ||
                (match.home + match.away + match.competition)
                    .contains(normalizedKeyword, ignoreCase = true))
    }
}
