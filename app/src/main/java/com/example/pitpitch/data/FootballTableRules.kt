package com.example.pitpitch.data

enum class TableZone(val label: String) {
    CHAMPIONS("欧冠席位"), CHAMPIONS_QUALIFYING("欧冠资格赛"), EUROPA("欧联席位"), CONFERENCE("欧协联席位"),
    ADVANCE("直接晋级"), PLAYOFF("晋级附加赛"), RELEGATION_PLAYOFF("保级附加赛"), RELEGATION("降级区"), ELIMINATED("淘汰区")
}
data class StandingMark(val zone: TableZone?, val confirmed: Boolean)

fun standingNoteZone(note: String?): TableZone? {
    val n = note?.lowercase() ?: return null
    return when {
        "relegation playoff" in n -> TableZone.RELEGATION_PLAYOFF
        "relegation" in n -> TableZone.RELEGATION
        "eliminated" in n -> TableZone.ELIMINATED
        "champions league" in n -> if ("qualif" in n) TableZone.CHAMPIONS_QUALIFYING else TableZone.CHAMPIONS
        "europa league" in n -> TableZone.EUROPA
        "conference" in n -> TableZone.CONFERENCE
        "playoff" in n || "play-off" in n -> TableZone.PLAYOFF
        "qualifies" in n || "qualified" in n || "round of" in n -> TableZone.ADVANCE
        else -> null
    }
}
fun standingMark(row: FootballStanding, table: FootballTable, league: String, year: Int): StandingMark {
    val position = row.position.toIntOrNull() ?: return StandingMark(null, false)
    val european = league.startsWith("uefa.")
    val n = table.entries.size
    val providerZone = standingNoteZone(row.providerNote)
    val fallback = if (european) {
        if (year >= 2024 && n == 36) when (position) { in 1..8 -> TableZone.ADVANCE; in 9..24 -> TableZone.PLAYOFF; in 25..36 -> TableZone.ELIMINATED; else -> null }
        else if (year < 2024 && n == 4) when {
            league == "uefa.champions" && position <= 2 -> TableZone.ADVANCE
            league == "uefa.champions" && position == 3 -> TableZone.EUROPA
            league == "uefa.europa" && year == 2020 -> if (position <= 2) TableZone.ADVANCE else TableZone.ELIMINATED
            league == "uefa.europa" && position == 1 -> TableZone.ADVANCE
            league == "uefa.europa" && position == 2 -> TableZone.PLAYOFF
            league == "uefa.europa" && position == 3 -> TableZone.CONFERENCE
            else -> TableZone.ELIMINATED
        } else null
    } else when {
        n !in listOf(18, 20) -> null
        league == "fra.1" && position <= 2 && year < 2023 -> TableZone.CHAMPIONS
        league == "fra.1" && position == 3 && year < 2023 -> TableZone.CHAMPIONS_QUALIFYING
        league == "fra.1" && position <= 3 && year >= 2023 -> TableZone.CHAMPIONS
        league == "fra.1" && position == 4 && year >= 2023 -> TableZone.CHAMPIONS_QUALIFYING
        league != "fra.1" && position <= 4 -> TableZone.CHAMPIONS
        league == "ger.1" && position == n - 2 -> TableZone.RELEGATION_PLAYOFF
        league == "fra.1" && year != 2022 && position == n - 2 -> TableZone.RELEGATION_PLAYOFF
        league == "fra.1" && year == 2022 && position >= n - 3 -> TableZone.RELEGATION
        league in listOf("ger.1", "fra.1") && position >= n - 1 -> TableZone.RELEGATION
        league in listOf("eng.1", "esp.1", "ita.1") && position >= n - 2 -> TableZone.RELEGATION
        else -> null
    }
    val expected = if (european) { if (year >= 2024) 8 else 6 } else (n - 1) * 2
    val complete = n > 0 && table.entries.all { it.played.toIntOrNull() == expected }
    val zone = providerZone ?: fallback
    val positionsValid = table.entries.mapNotNull { it.position.toIntOrNull() }.sorted() == (1..n).toList()
    val statsValid = table.entries.all { it.points.toIntOrNull() != null && it.played.toIntOrNull() in 0..expected }
    val mathConfirmed = if (!positionsValid || !statsValid || n !in listOf(4, 18, 20, 36)) false else {
        val points = row.points.toInt()
        val maximum = points + (expected - row.played.toInt()) * 3
        val others = table.entries.filter { it.team.id != row.team.id }
        val safeTop = when {
            european && year >= 2024 -> 8
            european && league == "uefa.europa" && year > 2020 -> 1
            european -> 2
            league == "fra.1" -> if (year >= 2023) 3 else 2
            else -> 4
        }
        val safeBottom = when {
            european && year >= 2024 -> 12
            european -> 1
            league == "fra.1" && year == 2022 -> 4
            league in listOf("fra.1", "ger.1") -> 2
            else -> 3
        }
        when (zone) {
            TableZone.CHAMPIONS, TableZone.ADVANCE -> position <= safeTop &&
                others.count { it.points.toInt() + (expected - it.played.toInt()) * 3 >= points } < safeTop
            TableZone.RELEGATION, TableZone.ELIMINATED -> others.count { it.points.toInt() > maximum } >= n - safeBottom
            else -> false
        }
    }
    return StandingMark(zone, (complete && providerZone != null) || mathConfirmed)
}
fun footballRulesText(league: String, year: Int): List<String> = buildList {
    add("胜 3 分、平 1 分、负 0 分；净胜球 = 进球 − 失球。")
    when (league) {
        "eng.1" -> {
            add("英超：20 队，每队 38 场；第 1 名为冠军，第 18–20 名降级。")
            add("同分先比较全季净胜球、进球数；仍相同按赛事规则处理。")
            add("基础欧冠席位为前 4 名。欧战名额可因当季额外席位及杯赛结果调整。")
        }
        "esp.1" -> {
            add("西甲：20 队，每队 38 场；第 1 名为冠军，第 18–20 名降级。")
            add("两队同分先比较相互比赛净胜球；多队同分先比较相互比赛积分、净胜球。之后均比较全季净胜球、进球数；仍相同按赛事规则处理。")
            add("基础欧冠席位为前 4 名。欧战名额可因当季额外席位及杯赛结果调整。")
        }
        "ita.1" -> {
            add("意甲：20 队，每队 38 场；第 1 名为冠军，第 18–20 名降级。")
            add("同分依次比较相互比赛积分、相互比赛净胜球、全季净胜球、进球数；${if (year >= 2022) "争冠/保级同分按当季附加赛规则处理，" else ""}仍相同按赛事规则处理。")
            add("基础欧冠席位为前 4 名。欧战名额可因当季额外席位及杯赛结果调整。")
        }
        "ger.1" -> {
            add("德甲：18 队，每队 34 场；第 1 名为冠军，第 17–18 名降级，第 16 名参加升降级附加赛。")
            add("同分先比较全季净胜球、进球数，再比较相互比赛总比分、相互比赛客场进球数、全季客场进球数；仍相同按赛事规则处理。")
            add("基础欧冠席位为前 4 名。欧战名额可因当季额外席位及杯赛结果调整。")
        }
        "fra.1" -> {
            when {
                year == 2022 -> add("法甲 2022/23：20 队，每队 38 场；第 1 名为冠军，第 17–20 名共 4 队降级，不设升降级附加赛。")
                year >= 2023 -> add("法甲自 2023/24 起：18 队，每队 34 场；第 1 名为冠军，第 17–18 名降级，第 16 名参加升降级附加赛。")
                else -> add("法甲：20 队，每队 38 场；第 1 名为冠军，第 19–20 名降级，第 18 名参加升降级附加赛。")
            }
            add("同分先比较全季净胜球，再比较相互比赛积分、净胜球、进球数；仍相同按赛事规则处理。")
            add("${if (year >= 2023) "基础欧冠席位为前 3 名正赛、第 4 名资格赛。" else "基础欧冠席位为前 2 名正赛、第 3 名资格赛。"}欧战名额可因当季额外席位及杯赛结果调整。")
        }
        "uefa.champions", "uefa.europa" -> {
            if (year >= 2024) {
                add("自 2024/25 起：36 队参加联赛阶段，每队对阵 8 个不同对手，共 8 场。第 1–8 名直接进十六强，第 9–24 名参加晋级附加赛，第 25–36 名淘汰。")
                add("同分先比较净胜球、进球数、客场进球数、胜场数、客场胜场数，后续比较对手成绩等，不优先比较相互战绩；仍相同按赛事规则处理。")
            } else {
                when {
                    league == "uefa.champions" -> add("欧冠小组制：每组 4 队，每队 6 场；前 2 名进十六强，第 3 名转入欧联${if (year >= 2021) "淘汰赛附加赛" else "三十二强"}，第 4 名淘汰。")
                    year == 2020 -> add("欧联 2020/21 小组制：每组 4 队，每队 6 场；前 2 名进三十二强，第 3、4 名淘汰。")
                    else -> add("欧联 2021/22–2023/24 小组制：每组 4 队，每队 6 场；第 1 名进十六强，第 2 名参加晋级附加赛，第 3 名转入欧协联淘汰赛附加赛，第 4 名淘汰。")
                }
                val headToHead = if (year < 2021) "积分、净胜球、进球数、客场进球数" else "积分、净胜球、进球数"
                add("同分先比较相互比赛$headToHead，再比较全部小组比赛的净胜球、进球数等；仍相同按赛事规则处理。")
            }
            add("${if (league == "uefa.champions") "欧冠" else "欧联"}冠军取得下赛季欧冠正赛资格；本赛事不设国内联赛式降级。")
        }
    }
}
