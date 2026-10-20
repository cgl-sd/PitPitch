package com.example.pitpitch.data

import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import java.util.Locale

data class TeamSelectionEntry(val team: FootballTeam, val displayName: String, val initial: String)

/** Chinese display names use the device's Chinese pinyin index; provider names stay unchanged. */
fun teamSelectionEntries(teams: List<FootballTeam>, names: Map<Int, String>): List<TeamSelectionEntry> {
    val index = AlphabeticIndex<Any>(Locale.SIMPLIFIED_CHINESE).addLabels(Locale.ENGLISH).buildImmutableIndex()
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    return teams.distinctBy { it.id }.map { team ->
        val name = names[team.id]?.takeIf(String::isNotBlank) ?: team.name.takeIf(String::isNotBlank) ?: PENDING_TEAM_NAME
        val label = index.getBucket(index.getBucketIndex(name))?.label.orEmpty()
        TeamSelectionEntry(team, name, label.takeIf { it.length == 1 && it[0] in 'A'..'Z' } ?: "#")
    }.sortedWith { a, b ->
        val group = (if (a.initial == "#") "[" else a.initial).compareTo(if (b.initial == "#") "[" else b.initial)
        if (group != 0) group else collator.compare(a.displayName, b.displayName).takeIf { it != 0 } ?: a.team.id.compareTo(b.team.id)
    }
}

fun TeamSelectionEntry.matchesTeamSearch(query: String): Boolean =
    displayName.contains(query, ignoreCase = true) || team.name.contains(query, ignoreCase = true)
