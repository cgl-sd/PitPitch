package com.example.pitpitch.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar focus only; never infer race results or standings from the device date. */
fun f1CalendarFocus(races: List<F1Race>, now: Instant, zone: ZoneId, completedIds: Set<String> = emptySet()): F1Race? =
    f1UpcomingRace(races, now, zone, completedIds) ?: races.minByOrNull { it.round }

/** Only this real upcoming/current-day race may expand; a historical focus never does. */
fun f1UpcomingRace(races: List<F1Race>, now: Instant, zone: ZoneId, completedIds: Set<String> = emptySet()): F1Race? {
    val today = now.atZone(zone).toLocalDate()
    var next: F1Race? = null
    for (race in races) {
        if (race.id in completedIds || (next != null && race.round >= next.round)) continue
        val eligible = runCatching {
            val date = race.utcTime?.let { Instant.parse("${race.date}T$it").atZone(zone).toLocalDate() }
                ?: LocalDate.parse(race.date)
            !date.isBefore(today)
        }.getOrDefault(false)
        if (eligible) next = race
    }
    return next
}
