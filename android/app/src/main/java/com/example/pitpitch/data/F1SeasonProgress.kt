package com.example.pitpitch.data

/** Progress follows the published standings round, never inferred race results. */
data class F1SeasonProgress(val counted: Int, val total: Int) {
    val remaining get() = total - counted
    val fraction get() = counted.toFloat() / total
}

fun f1SeasonProgress(season: Int, races: List<F1Race>, standingsRound: String?): F1SeasonProgress? {
    val round = standingsRound?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val rounds = races.filter { it.season == season && it.round > 0 }.map { it.round }.distinct()
    if (rounds.isEmpty() || round > rounds.max()) return null
    return F1SeasonProgress(rounds.count { it <= round }, rounds.size)
}
