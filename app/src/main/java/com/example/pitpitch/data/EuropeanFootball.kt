package com.example.pitpitch.data

import org.json.JSONObject
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Fixed archive start, including the ongoing season. */
const val ARCHIVE_START_YEAR = 2020
fun footballArchiveYears(end: Int = Year.now().value): IntRange = ARCHIVE_START_YEAR..end
fun f1ArchiveYears(end: Int = Year.now().value): IntRange = ARCHIVE_START_YEAR..end

data class FootballCompetition(val id: String, val title: String, val european: Boolean = false)
val footballCompetitions = listOf(
    FootballCompetition("eng.1", "英超"), FootballCompetition("esp.1", "西甲"),
    FootballCompetition("ita.1", "意甲"), FootballCompetition("ger.1", "德甲"),
    FootballCompetition("fra.1", "法甲"), FootballCompetition("uefa.champions", "欧冠", true),
    FootballCompetition("uefa.europa", "欧联", true),
)
data class FootballStanding(val team: FootballTeam, val position: String, val played: String,
    val won: String, val drawn: String, val lost: String, val goalsFor: String, val goalsAgainst: String,
    val difference: String, val points: String, val deductions: String?, val providerNote: String? = null)
data class FootballTable(val name: String, val entries: List<FootballStanding>)
data class FootballPlayer(val id: String, val name: String, val jersey: String?, val position: String,
    val goals: String?, val appearances: String?, val assists: String?, val photoUrl: String? = null,
    val tackles: String? = null, val dribbles: String? = null)
data class FootballSquad(val season: Int, val players: List<FootballPlayer>,
    val coach: String? = null)
data class FootballArchiveSeason(val matches: List<FootballMatch>, val tables: Map<String, RemoteSnapshot<List<FootballTable>>>,
    val fetchedUtc: String?, val errors: List<String> = emptyList())

fun europeanStage(note: String): String {
    val phase = note.substringAfter(", ", note).trim()
    val lower = phase.lowercase()
    val group = Regex("group\\s+([a-z])$", RegexOption.IGNORE_CASE).find(phase)
    return when {
        group != null -> "小组 ${group.groupValues[1].uppercase()}"
        lower == "league phase" -> "联赛阶段"
        lower.endsWith("round of 32") -> "32 强"
        lower.endsWith("round of 16") -> "16 强"
        lower.endsWith("quarterfinals") -> "八强"
        lower.endsWith("semifinals") -> "半决赛"
        lower == "final" || lower.endsWith(" final") -> "决赛"
        lower.contains("knockout") -> "淘汰赛附加赛"
        lower.contains("playoff") || lower.contains("play-off") -> "资格赛附加赛"
        lower.contains("preliminary") -> "资格赛预赛"
        lower.contains("first") -> "资格赛第一轮"
        lower.contains("second") -> "资格赛第二轮"
        lower.contains("third") -> "资格赛第三轮"
        else -> phase.takeIf { it.isNotBlank() } ?: "阶段未提供"
    }
}
fun isKnockoutStage(stage: String): Boolean = listOf("32 强", "16 强", "八强", "半决赛", "决赛", "淘汰赛").any { stage.startsWith(it) }
fun isEuropeanGroupStage(stage: String): Boolean = stage.startsWith("小组 ") || stage.startsWith("联赛阶段")
/** Stable unordered pair only within the source stage. Never invent links between rounds. */
fun knockoutTieKey(match: FootballMatch): String = listOf(match.homeTeamId?.toString() ?: match.home, match.awayTeamId?.toString() ?: match.away).sorted().joinToString("|") + "|${match.stage}"

/** UTC dates (YYYYMMDD) covering zone-yesterday and zone-today, so late-night kickoffs and last
 *  night's just-finished games both refresh; a Beijing day always spans two UTC dates. */
fun overlayUtcDays(now: Instant, zone: ZoneId): List<String> = runCatching {
    val today = now.atZone(zone).toLocalDate()
    val first = today.minusDays(1).atStartOfDay(zone).toInstant().atZone(ZoneOffset.UTC).toLocalDate()
    val last = today.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1).atZone(ZoneOffset.UTC).toLocalDate()
    generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
        .map { it.format(DateTimeFormatter.BASIC_ISO_DATE) }.toList()
}.getOrDefault(emptyList())

/** List rows append the provider clock for in-progress games; "进行" filtering and VS logic stay untouched. */
fun liveStatusText(match: FootballMatch): String =
    if (match.liveDetail.isNullOrBlank()) match.status else "${match.status} · ${match.liveDetail}"

data class FootballScoreboard(val matches: List<FootballMatch>, val timeline: Map<String, List<MatchEvent>>)

object EspnFootballAdapter {
    const val SITE = "https://site.api.espn.com/apis/site/v2/sports/soccer/"
    const val WEB = "https://site.web.api.espn.com/apis/v2/sports/soccer/"
    /** Day-level scoreboard for timely in-progress/just-finished games; season filter still applies at parse time. */
    fun dayUrl(league: FootballCompetition, utcDay: String) = "${SITE}${league.id}/scoreboard?dates=$utcDay&limit=100"
    fun scheduleUrl(leagueId: String, calendarYear: Int) = "${SITE}$leagueId/scoreboard?dates=$calendarYear&limit=1000"
    fun standingsUrl(leagueId: String, season: Int) = "${WEB}$leagueId/standings?season=$season"
    fun scoreboard(body: String, league: FootballCompetition, year: Int): FootballScoreboard {
        val root = JSONObject(body)
        return FootballScoreboard(matches(root, league, year), timeline(root, league, year))
    }
    // Provider namespace prevents a saved OpenLigaDB team ID from selecting an unrelated ESPN team.
    private fun id(raw: JSONObject) = raw.getString("id").toInt() + 1_000_000
    fun team(raw: JSONObject): FootballTeam = FootballTeam(id(raw), raw.getString("displayName"),
        secureBadgeUrl(raw.text("logo") ?: raw.optJSONArray("logos")?.optJSONObject(0)?.text("href")))
    /** Compact goal/card timelines stored inside archived scoreboards; substitutions live only in summaries. */
    fun timeline(body: String, league: FootballCompetition, season: Int): Map<String, List<MatchEvent>> =
        timeline(JSONObject(body), league, season)
    private fun timeline(root: JSONObject, league: FootballCompetition, season: Int): Map<String, List<MatchEvent>> =
        root.getJSONArray("events").objects().asSequence()
            .filter { it.getJSONObject("season").getInt("year") == season }
            .mapNotNull { event ->
                val id = "fb-espn-${league.id}-${event.getString("id")}"
                val details = event.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("details") ?: return@mapNotNull null
                val rows = details.objects().mapIndexedNotNull { _, d ->
                    val type = d.optJSONObject("type")?.text("type") ?: d.text("type") ?: return@mapIndexedNotNull null
                    val players = (d.optJSONArray("players") ?: d.optJSONArray("athletes"))?.objects().orEmpty().mapNotNull { it.text("name") ?: it.text("displayName") }
                    val base = if (d.optBoolean("own", false)) "乌龙球" else EspnMatchAdapter.eventTypeLabel(type) ?: type
                    val label = if (players.isNotEmpty()) "${players[0]} $base" else base
                    val clock = Regex("^(\\d+)(?:'\\+(\\d+))?").find((d.optJSONObject("clock")?.text("displayValue") ?: d.text("clock")).orEmpty())
                    MatchEvent(clock?.groupValues?.get(1)?.toIntOrNull() ?: 0, clock?.groupValues?.get(2)?.toIntOrNull() ?: 0, label)
                }
                if (rows.isEmpty()) null else id to rows
            }.toMap()
    fun matches(body: String, league: FootballCompetition, year: Int): List<FootballMatch> =
        matches(JSONObject(body), league, year)
    private fun matches(root: JSONObject, league: FootballCompetition, year: Int): List<FootballMatch> = root.getJSONArray("events").objects()
        .filter { it.getJSONObject("season").getInt("year") == year }.mapNotNull { event ->
            val game = event.getJSONArray("competitions").getJSONObject(0)
            val teams = game.getJSONArray("competitors").objects()
            val h = teams.find { it.text("homeAway") == "home" } ?: return@mapNotNull null
            val a = teams.find { it.text("homeAway") == "away" } ?: return@mapNotNull null
            val home = team(h.getJSONObject("team")); val away = team(a.getJSONObject("team"))
            val status = game.getJSONObject("status").getJSONObject("type")
            val cancelled = status.text("name").orEmpty().contains("CANCEL")
            val postponed = status.text("name").orEmpty().contains("POSTPON")
            val phase = if (league.european) europeanStage(game.text("altGameNote").orEmpty()) else "常规联赛 · 轮次未提供"
            val kickoff = event.getString("date")
            val played = status.text("state") in listOf("post", "in") && !postponed && !cancelled
            FootballMatch(id = "fb-espn-${league.id}-${event.getString("id")}", competition = league.title, stage = phase,
                home = home.name, away = away.name, homeShort = h.getJSONObject("team").text("shortDisplayName") ?: home.name,
                awayShort = a.getJSONObject("team").text("shortDisplayName") ?: away.name,
                score = if (played && h.text("score") != null && a.text("score") != null) "${h.getString("score")} : ${a.getString("score")}" else "— : —",
                status = when { cancelled -> "取消"; postponed -> "延期"; status.optBoolean("completed") -> "完场";
                    status.text("state") == "in" -> "进行中"; else -> "待赛" },
                stadium = game.optJSONObject("venue")?.text("fullName") ?: "场地未提供",
                date = kickoff.take(10), kickoffUtc = if (game.optBoolean("timeValid", true)) runCatching { parseProviderInstant(kickoff) }.getOrNull() else null,
                lineupAvailable = false, eventsAvailable = false, statsAvailable = false, isDemo = false, source = "ESPN 公开数据 · 非官方接口",
                homeTeamId = home.id, awayTeamId = away.id, homeBadgeUrl = home.badgeUrl, awayBadgeUrl = away.badgeUrl,
                competitionId = league.id, leg = game.optJSONObject("leg")?.optInt("value")?.takeIf { it > 0 },
                liveDetail = if (status.text("state") == "in" && !cancelled && !postponed) {
                    if (status.text("name").orEmpty().contains("HALFTIME")) "HT"
                    else providerMatchClock(game.getJSONObject("status").text("displayClock") ?: status.text("detail"),
                        game.getJSONObject("status").optDouble("clock").takeIf { it.isFinite() }) ?: status.text("detail")
                } else null)
        }
    fun tables(body: String, year: Int): List<FootballTable> {
        val root = JSONObject(body)
        require(root.getJSONObject("season").getInt("year") == year) { "Provider returned a different season" }
        fun read(group: JSONObject): List<FootballTable> {
            val children = group.optJSONArray("children")?.objects().orEmpty().flatMap(::read)
            val entries = group.optJSONObject("standings")?.optJSONArray("entries")?.objects().orEmpty()
            if (entries.isEmpty()) return children
            val rows = entries.map { entry ->
                val values = entry.getJSONArray("stats").objects().associate { it.getString("name") to (it.text("displayValue") ?: it.text("value") ?: "—") }
                fun stat(key: String) = values[key] ?: "—"
                FootballStanding(team(entry.getJSONObject("team")), stat("rank"), stat("gamesPlayed"), stat("wins"), stat("ties"), stat("losses"),
                    stat("pointsFor"), stat("pointsAgainst"), stat("pointDifferential"), stat("points"), values["deductions"]?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }, entry.optJSONObject("note")?.text("description"))
            }
            return children + FootballTable(europeanStage(group.text("name").orEmpty()), rows)
        }
        return read(root)
    }
    fun squad(body: String, year: Int): FootballSquad {
        val root = JSONObject(body)
        require(root.getJSONObject("season").getInt("year") == year) { "Provider returned a different squad season" }
        val players = root.getJSONArray("athletes").objects().map { athlete ->
            val stats = athlete.optJSONObject("statistics")?.optJSONObject("splits")?.optJSONArray("categories")?.objects().orEmpty()
                .flatMap { it.optJSONArray("stats")?.objects().orEmpty() }.associate { it.getString("name") to (it.text("displayValue") ?: it.text("value")) }
            val position = athlete.optJSONObject("position")?.text("name")
            FootballPlayer(athlete.getString("id"), athlete.getString("displayName"), athlete.text("jersey"), when (position) {
                "Goalkeeper" -> "门将"; "Defender" -> "后卫"; "Midfielder" -> "中场"; "Forward" -> "前锋"; else -> position ?: "位置未提供"
            }, stats["totalGoals"], stats["appearances"], stats["goalAssists"], secureBadgeUrl(athlete.optJSONObject("headshot")?.text("href")),
                stats["totalTackles"], stats["successfulDribbles"])
        }
        // Do not treat ESPN's unversioned, stale coach record as a verified historical head coach.
        return FootballSquad(year, players)
    }
}
