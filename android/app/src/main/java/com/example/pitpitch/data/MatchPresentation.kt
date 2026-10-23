package com.example.pitpitch.data

/** Format the provider clock only; never advance a clock from wall time. */
internal fun providerMatchClock(display: String?, seconds: Double?): String? {
    val text = display?.trim().orEmpty()
    if (Regex("\\d+(?:\\+\\d+)?:[0-5]\\d").matches(text)) return text
    val added = Regex("^(\\d+)['′]?\\+(\\d+)['′]?$").matchEntire(text)
    val clock = seconds?.takeIf { it.isFinite() && it >= 0 }?.toLong()
    // Provider clock may stop at the end of normal time while displayClock reports added minutes.
    if (added != null) {
        val base = added.groupValues[1].toLongOrNull()
        val extra = added.groupValues[2].toLongOrNull()
        val label = "${added.groupValues[1]}+${added.groupValues[2]}"
        return if (clock != null && base != null && extra != null && clock / 60 == base + extra)
            "$label:${(clock % 60).toString().padStart(2, '0')}" else "$label′"
    }
    if (clock != null) return "${clock / 60}:${(clock % 60).toString().padStart(2, '0')}"
    return text.takeIf { Regex("\\d+['′]").matches(it) }
}

fun matchScoreStatus(match: FootballMatch): String {
    if (!match.status.contains("进行")) return match.status
    val detail = match.liveDetail?.trim().orEmpty()
    if (detail.equals("HT", true) || detail.contains("halftime", true) || detail == "中场") return "中场"
    return providerMatchClock(detail, null) ?: "—"
}

fun compactMatchStatistics(statistics: List<MatchStatistic>): List<MatchStatistic> =
    listOf("控球率", "射门", "射正", "角球", "犯规", "黄牌", "红牌", "点球进球")
        .mapNotNull { label -> statistics.firstOrNull { it.label == label } }
