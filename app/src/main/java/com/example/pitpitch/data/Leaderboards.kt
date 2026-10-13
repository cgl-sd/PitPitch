package com.example.pitpitch.data

/** Season scoring/assists ranking computed locally from archived rosters; no extra provider requests. */
data class LeaderEntry(val name: String, val team: String, val goals: Int?, val assists: Int?, val appearances: Int?, val teamId: Int? = null, val playerId: String? = null)

fun aggregateLeaderboards(squads: List<Pair<String, FootballSquad>>): List<LeaderEntry> =
    squads.flatMap { (team, squad) ->
        squad.players.map {
            LeaderEntry(it.name, team, it.goals?.toIntOrNull(), it.assists?.toIntOrNull(), it.appearances?.toIntOrNull(), playerId = it.id)
        }
    }.sortedWith(compareByDescending<LeaderEntry> { it.goals }.thenByDescending { it.assists }
        .thenByDescending { it.appearances }.thenBy { it.name })
