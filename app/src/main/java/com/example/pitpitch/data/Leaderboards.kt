package com.example.pitpitch.data

/** Season scoring/assists ranking computed locally from archived rosters; no extra provider requests. */
data class LeaderEntry(val name: String, val team: String, val goals: Int?, val assists: Int?, val appearances: Int?, val teamId: Int? = null, val playerId: String? = null)

/** Single ranking rule for scoring and assists lists; unknown values sort last, ties break by name. */
fun List<LeaderEntry>.rankLeaderboard(): List<LeaderEntry> =
    sortedWith(compareByDescending<LeaderEntry> { it.goals }.thenByDescending { it.assists }
        .thenByDescending { it.appearances }.thenBy { it.name })
