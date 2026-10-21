package com.example.pitpitch.data

import org.json.JSONObject

data class MatchLineup(val teamId: Int, val formation: String?, val starters: List<FootballPlayer>, val substitutes: List<FootballPlayer>, val unknown: List<FootballPlayer>)
data class MatchStatistic(val label: String, val home: String?, val away: String?)
/** A supplied complete section may be empty; an omitted or partial section must not erase cached facts. */
data class FootballDetail(
    val lineups: List<MatchLineup>,
    val statistics: List<MatchStatistic>,
    val events: List<FootballDetailEvent> = emptyList(),
    val lineupsProvided: Boolean = true,
    val statisticsProvided: Boolean = true,
    val eventsProvided: Boolean = true,
)
data class FootballDetailEvent(val id: String, val type: String, val clock: String, val seconds: Double?, val period: Int?, val teamId: Int?, val players: List<String>, val text: String,
    val sourceScore: String? = null)

/** Reconcile this match's published positions with its formation, never a player's usual role.
 * A broad role may span adjacent lines (e.g. the two supporting forwards in 3-4-2-1).
 * Render only when the complete eleven has exactly one valid row assignment.
 */
fun publishedLineupRows(lineup: MatchLineup): List<List<FootballPlayer>>? {
    val shape = lineup.formation?.split("-", "–")?.map { it.trim().toIntOrNull() ?: return null } ?: return null
    if (shape.size !in 2..5 || shape.any { it !in 1..5 } || shape.sum() != 10 ||
        lineup.starters.size != 11 || lineup.starters.map { it.id }.toSet().size != 11) return null
    val last = shape.size
    val middle = (2 until last).toList()
    val candidates = lineup.starters.map { player ->
        val position = player.position.lowercase()
        when {
            "goalkeeper" in position -> listOf(0)
            "wing back" in position || "wing-back" in position -> listOf(1) + middle.take(1)
            "defender" in position || "back" in position -> listOf(1)
            "midfielder" in position -> when {
                "attacking" in position -> middle.takeLast(1)
                "defensive" in position -> middle.take(1)
                else -> middle
            }
            "forward" in position || "striker" in position -> {
                // ESPN supplies these as forwards even when they are the supporting pair.
                val wide = "left" in position || "right" in position
                if (wide && middle.size > 1) listOf(last - 1, last) else listOf(last)
            }
            else -> emptyList()
        }
    }
    if (candidates.any { it.isEmpty() }) return null
    val remaining = (listOf(1) + shape).toIntArray()
    val assignment = IntArray(11)
    val order = candidates.indices.sortedBy { candidates[it].size }
    var solution: IntArray? = null
    var solutions = 0
    var visits = 0
    fun assign(index: Int) {
        if (solutions > 1 || ++visits > 10000) return
        if (index == order.size) {
            if (remaining.all { it == 0 }) { solutions++; solution = assignment.copyOf() }
            return
        }
        val player = order[index]
        for (row in candidates[player]) {
            if (remaining[row] == 0) continue
            remaining[row]--
            assignment[player] = row
            assign(index + 1)
            remaining[row]++
        }
    }
    assign(0)
    if (solutions != 1 || visits > 10000) return null
    fun horizontal(player: FootballPlayer) = when {
        player.position.contains("Center Left", true) -> 1
        player.position.contains("Center Right", true) -> 3
        player.position.contains("Left", true) -> 0
        player.position.contains("Right", true) -> 4
        else -> 2
    }
    return remaining.indices.map { row ->
        lineup.starters.filterIndexed { index, _ -> solution!![index] == row }.sortedBy(::horizontal)
    }
}

/** Always diagram confirmed starters. Unresolved placement is explicitly labelled schematic by UI.
 * This projection never changes the stored formation, positions, participants or starter flags.
 */
fun lineupRows(lineup: MatchLineup): List<List<FootballPlayer>>? =
    publishedLineupRows(lineup) ?: schematicLineupRows(lineup)

fun schematicLineupRows(lineup: MatchLineup): List<List<FootballPlayer>>? {
    val players = lineup.starters.distinctBy { it.id }
    if (players.isEmpty()) return null
    fun depth(player: FootballPlayer): Int {
        val position = player.position.lowercase()
        return when {
            "goalkeeper" in position -> 0
            "wing back" in position || "wing-back" in position -> 2
            "defender" in position || "back" in position -> 1
            "defensive" in position -> 2
            "attacking" in position -> 4
            "midfielder" in position -> 3
            ("forward" in position || "striker" in position) && ("left" in position || "right" in position) -> 4
            "forward" in position || "striker" in position -> 5
            else -> 3
        }
    }
    fun horizontal(player: FootballPlayer): Int = when {
        player.position.contains("Center Left", true) -> 1
        player.position.contains("Center Right", true) -> 3
        player.position.contains("Left", true) -> 0
        player.position.contains("Right", true) -> 4
        else -> 2
    }
    val ordered = players.sortedBy(::depth)
    val shape = lineup.formation?.split("-", "–")?.mapNotNull { it.trim().toIntOrNull() }
    if (players.size == 11 && shape != null && shape.size in 2..5 &&
        shape.all { it in 1..5 } && shape.sum() == 10 &&
        lineup.formation?.split("-", "–")?.size == shape.size) {
        var offset = 0
        return (listOf(1) + shape).map { count ->
            ordered.subList(offset, offset + count).sortedBy(::horizontal).also { offset += count }
        }
    }
    // No formation is claimed: keep known broad lines and distribute each over readable rows.
    return ordered.groupBy { player -> when (depth(player)) { 0 -> 0; 1 -> 1; 2, 3 -> 2; else -> 3 } }
        .values.flatMap { it.sortedBy(::horizontal).chunked(4) }
}

object EspnMatchAdapter {
    fun eventTypeLabel(type: String): String? = when (type.lowercase()) {
        "goal", "goal---header", "goal---volley" -> "进球"
        "own-goal" -> "乌龙球"
        "yellow-card" -> "黄牌"
        "red-card", "second-yellow-card" -> "红牌"
        "substitution" -> "换人"
        else -> null
    }

    fun detail(body: String, match: FootballMatch): FootballDetail {
        val root = JSONObject(body)
        require(root.getJSONObject("header").getString("id") == match.id.substringAfterLast('-')) { "Unrelated event" }
        val ids = root.getJSONObject("header").getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors").objects().map { it.getString("id").toInt() + 1_000_000 }.toSet()
        require(ids == setOf(match.homeTeamId, match.awayTeamId)) { "Unrelated teams" }
        // Commentary uses the provider's short names in some competitions (e.g. Union Berlin).
        // Only aliases attached to the already validated team IDs can identify a score statement.
        val aliases = root.getJSONObject("header").getJSONArray("competitions").getJSONObject(0)
            .getJSONArray("competitors").objects().associate { competitor ->
                val team = competitor.optJSONObject("team")
                competitor.getString("id").toInt() + 1_000_000 to
                    listOfNotNull(team?.text("displayName"), team?.text("shortDisplayName"), team?.text("name")).filter { it.isNotBlank() }.distinct()
            }
        val lineups = root.optJSONArray("rosters")?.objects().orEmpty().map { roster ->
            val teamId = roster.getJSONObject("team").getString("id").toInt() + 1_000_000
            require(teamId in ids)
            val players = roster.optJSONArray("roster")?.objects().orEmpty().map { entry ->
                val athlete = entry.getJSONObject("athlete")
                val stats = entry.optJSONArray("stats")?.objects().orEmpty().associate { it.getString("name") to it.text("displayValue") }
                FootballPlayer(athlete.getString("id"), athlete.getString("displayName"), entry.text("jersey"), entry.optJSONObject("position")?.text("displayName") ?: "位置未提供",
                    stats["totalGoals"], stats["appearances"], stats["goalAssists"], secureBadgeUrl(athlete.optJSONObject("headshot")?.text("href"))) to
                    if (entry.has("starter") && !entry.isNull("starter")) entry.getBoolean("starter") else null
            }
            MatchLineup(teamId, roster.text("formation"), players.filter { it.second == true }.map { it.first },
                players.filter { it.second == false }.map { it.first }, players.filter { it.second == null }.map { it.first })
        }
        val teams = root.optJSONObject("boxscore")?.optJSONArray("teams")?.objects().orEmpty().associate { team ->
            team.getJSONObject("team").getString("id").toInt() + 1_000_000 to team.optJSONArray("statistics")?.objects().orEmpty().associate { it.getString("name") to it.text("displayValue") }
        }
        val labels = linkedMapOf(
            "possessionPct" to "控球率", "totalShots" to "射门", "shotsOnTarget" to "射正", "shotPct" to "射门转化率",
            "wonCorners" to "角球", "foulsCommitted" to "犯规", "yellowCards" to "黄牌", "redCards" to "红牌", "offsides" to "越位",
            "saves" to "扑救", "penaltyKickGoals" to "点球进球", "penaltyKickShots" to "点球射门",
            "accuratePasses" to "成功传球", "totalPasses" to "传球总数", "passPct" to "传球成功率",
            "accurateCrosses" to "成功传中", "totalCrosses" to "传中", "crossPct" to "传中成功率",
            "totalLongBalls" to "长传", "accurateLongBalls" to "成功长传", "longballPct" to "长传成功率",
            "blockedShots" to "封堵射门", "totalTackles" to "球队抢断尝试", "effectiveTackles" to "球队成功抢断", "tacklePct" to "抢断成功率",
            "interceptions" to "球队拦截", "effectiveClearance" to "成功解围", "totalClearance" to "解围",
        )
        val statistics = labels.mapNotNull { (key, label) ->
            val h = teams[match.homeTeamId]?.get(key); val a = teams[match.awayTeamId]?.get(key)
            if (h == null && a == null) null else MatchStatistic(label, h, a)
        }
        val events = root.optJSONArray("keyEvents")?.objects().orEmpty().map { event ->
            val teamId = event.optJSONObject("team")?.text("id")?.toIntOrNull()?.plus(1_000_000)
            require(teamId == null || teamId in ids) { "Unrelated event team" }
            val clock = event.optJSONObject("clock")
            val text = event.text("text") ?: event.text("shortText") ?: event.optJSONObject("type")?.text("text") ?: "事件"
            val sourceScore = aliases[match.homeTeamId].orEmpty().firstNotNullOfOrNull { home ->
                aliases[match.awayTeamId].orEmpty().firstNotNullOfOrNull { away -> explicitFootballEventScore(text, home, away) }
            }
            FootballDetailEvent(event.getString("id"), event.optJSONObject("type")?.text("type") ?: "unknown",
                clock?.text("displayValue").orEmpty(), if (clock?.has("value") == true) clock.optDouble("value").takeIf { it.isFinite() } else null,
                event.optJSONObject("period")?.optInt("number"), teamId,
                event.optJSONArray("participants")?.objects().orEmpty().mapNotNull { it.optJSONObject("athlete")?.text("displayName") },
                text, sourceScore)
        }.distinctBy { it.id }.sortedWith(compareBy<FootballDetailEvent> { it.period ?: Int.MAX_VALUE }.thenBy { it.seconds ?: Double.MAX_VALUE })
        val rosterSection = root.optJSONArray("rosters")
        val lineupComplete = rosterSection != null && (rosterSection.length() == 0 ||
            (lineups.map { it.teamId }.toSet() == ids &&
                rosterSection.objects().all { it.optJSONArray("roster") != null }))
        val teamSection = root.optJSONObject("boxscore")?.optJSONArray("teams")
        val statsComplete = teamSection != null && (teamSection.length() == 0 ||
            (teams.keys == ids && teamSection.objects().all { it.optJSONArray("statistics") != null }))
        return FootballDetail(lineups, statistics, events, lineupComplete, statsComplete, root.optJSONArray("keyEvents") != null)
    }
}
