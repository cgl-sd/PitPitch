package com.example.pitpitch.data

/** Deterministic fixtures for presentation, schedule and delivery tests. Test sources only, never shipped. */

/** UI prototypes only. Never imply real fixtures, confirmed lineups, or live scores. */
val sampleMatches = listOf(
    FootballMatch("fb-demo-1", "演示 · 英格兰联赛", "第 8 轮", "蓝城 FC", "红联 FC", "蓝城", "红联", "2 : 1", "演示完场", "城市体育场"),
    FootballMatch("fb-demo-2", "演示 · 欧洲赛事", "联赛阶段第 3 轮", "北伦敦队", "米兰蓝黑队", "北伦敦", "蓝黑", "— : —", "演示待赛", "新球场"),
    FootballMatch("fb-demo-3", "演示 · 西班牙联赛", "第 9 轮", "马德里白队", "巴塞罗那队", "马德里", "巴萨", "1 : 1", "演示进行中", "首都体育场"),
)

fun demoEvents(match: FootballMatch): List<MatchEvent> {
    if (!match.eventsAvailable || match.status.contains("延期") || match.status.contains("取消")) return emptyList()
    val events = listOf(
        MatchEvent(12, label = "${match.home} 进球"),
        MatchEvent(28, label = "${match.away} 黄牌"),
        MatchEvent(45, 2, "${match.home} 黄牌"),
        MatchEvent(51, label = "${match.home} 进球"),
        MatchEvent(61, label = "${match.home} 换人"),
        MatchEvent(76, label = "${match.away} 进球"),
        MatchEvent(90, 3, "比赛结束"),
    )
    return events.filter { !match.status.contains("进行") || (it.minute <= 76 && it.minute != 51) }
        .sortedWith(compareBy({ it.minute }, { it.added }))
}
