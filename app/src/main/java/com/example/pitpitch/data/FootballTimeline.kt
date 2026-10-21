package com.example.pitpitch.data

/** Presentation grouping only. Original event IDs, participants and order remain intact. */
data class FootballTimelineGroup(val events: List<FootballDetailEvent>) {
    val first get() = events.first()
}

/** Compact visible timeline; the full validated event list remains stored unchanged. */
fun compactFootballEvents(events: List<FootballDetailEvent>): List<FootballDetailEvent> = events.filter {
    it.type.startsWith("goal") || it.type.contains("own-goal") || it.type == "penalty---scored" ||
        it.type.contains("red-card") || it.type.contains("second-yellow") || it.type in setOf(
            "kickoff", "halftime", "start-2nd-half", "end-regular-time", "end-extra-time", "end-match", "stoppage-time")
}

/** Scores explicitly stated by the source, matched against both actual provider team names. */
fun footballEventScore(event: FootballDetailEvent, match: FootballMatch): String? {
    return event.sourceScore ?: explicitFootballEventScore(event.text, match.home, match.away)
}

internal fun explicitFootballEventScore(text: String, home: String, away: String): String? {
    val score = Regex("${Regex.escape(home)} (\\d+), ${Regex.escape(away)} (\\d+)(?:[.\\s]|$)")
        .find(text) ?: return null
    return "${score.groupValues[1]}-${score.groupValues[2]}"
}

fun footballTimelineGroups(events: List<FootballDetailEvent>): List<FootballTimelineGroup> {
    val groups = mutableListOf<FootballTimelineGroup>()
    for (event in events) {
        val previous = groups.lastOrNull()?.first
        val merge = event.type == "substitution" && previous?.type == "substitution" &&
            event.teamId != null && event.teamId == previous.teamId &&
            event.period != null && event.period == previous.period &&
            event.clock.isNotBlank() && event.clock == previous.clock
        if (merge) groups[groups.lastIndex] = FootballTimelineGroup(groups.last().events + event)
        else groups += FootballTimelineGroup(listOf(event))
    }
    return groups
}
