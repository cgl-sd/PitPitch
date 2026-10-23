package com.example.pitpitch.data

import org.json.JSONObject

/** Jolpica supplies schedules. It is NOT a live F1 timing service. */
object F1Api {
    private val sessionFields = listOf(
        "FirstPractice" to "第一次练习赛",
        "SecondPractice" to "第二次练习赛",
        "ThirdPractice" to "第三次练习赛",
        "SprintQualifying" to "冲刺排位赛",
        "Sprint" to "冲刺赛",
        "Qualifying" to "排位赛",
    )
    internal fun sessionOrder(title: String): Int =
        sessionFields.indexOfFirst { it.second == title }.let { if (it < 0) Int.MAX_VALUE else it }

    fun parseSeason(json: String, year: Int): List<F1Race> {
            val root = JSONObject(json).getJSONObject("MRData")
            val table = root.getJSONObject("RaceTable")
            require(table.getString("season").toInt() == year) { "Provider returned a different calendar season" }
            val races = table.getJSONArray("Races")
            require(root.getString("total").toInt() == races.length()) { "Provider returned an incomplete calendar" }
            val rounds = races.objects().map { race ->
                require(race.getString("season").toInt() == year)
                race.getString("round").toInt().also { require(it > 0) }
            }
            require(rounds.distinct().size == rounds.size) { "Provider returned duplicate calendar rounds" }
            val result = (0 until races.length()).map { index ->
                val race = races.getJSONObject(index)
                val circuit = race.getJSONObject("Circuit")
                val location = circuit.optJSONObject("Location")
                val entries = sessionFields.mapNotNull { (apiKey, label) ->
                    race.optJSONObject(apiKey)?.let { session ->
                        val date = session.optString("date")
                        if (date.isBlank()) null
                        else F1Session(label, date, session.optString("time").takeIf { it.isNotBlank() })
                    }
                }
                F1Race(
                    season = race.getString("season").toInt(),
                    round = rounds[index],
                    name = race.getString("raceName"),
                    circuit = circuit.optString("circuitName"),
                    date = race.getString("date"),
                    utcTime = race.optString("time").takeIf { it.isNotBlank() },
                    country = location?.optString("country").orEmpty(),
                    sessions = entries, circuitId = circuit.text("circuitId"),
                )
            }
            return result
    }
}
