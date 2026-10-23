package com.example.pitpitch.data

private val practiceTitles = setOf("练习赛", "第一次练习赛", "第二次练习赛", "第三次练习赛")

/** Display one genuine practice; keep original titles for persistence and booking IDs. */
fun F1Session.displayTitle(): String = if (title in practiceTitles) "练习赛" else title

fun F1Race.displaySessions(includeRace: Boolean = false, homePreview: Boolean = false): List<F1Session> {
    val order = compareBy<F1Session>({ it.date }, { it.time.orEmpty() })
    val practice = sessions.filter { it.title in practiceTitles }.let { entries ->
        entries.filter { it.title == "第一次练习赛" }.minWithOrNull(order) ?: entries.minWithOrNull(order)
    }
    val visible = sessions.filter { it.title !in practiceTitles && it.title != "正赛" && (!homePreview || it.title == "排位赛") } + listOfNotNull(practice)
    return (visible + if (includeRace) listOf(F1Session("正赛", date, utcTime)) else emptyList())
        .distinctBy { it.title }.sortedWith(order)
}
