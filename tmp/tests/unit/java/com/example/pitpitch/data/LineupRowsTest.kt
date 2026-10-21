package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class LineupRowsTest {
    private fun lineup(shape: String, positions: List<String>) = MatchLineup(1, shape,
        positions.mapIndexed { i, position -> FootballPlayer("$i", "Player $i", "$i", position, null, null, null) }, emptyList(), emptyList())
    private val leeds = listOf("Goalkeeper", "Center Defender", "Center Left Defender", "Center Right Defender",
        "Center Left Midfielder", "Center Right Midfielder", "Left Midfielder", "Right Midfielder",
        "Forward", "Center Left Forward", "Center Right Forward")

    @Test fun supportingForwardsOccupyTheirOwnLineWithoutChangingPublishedRoles() {
        val team = lineup("3-4-2-1", leeds)
        val rows = publishedLineupRows(team)!!
        assertEquals(listOf(1, 3, 4, 2, 1), rows.map { it.size })
        assertEquals(listOf("9", "10"), rows[3].map { it.id })
        assertEquals("8", rows[4].single().id)
        assertEquals(team.starters.toSet(), rows.flatten().toSet())
        assertEquals(rows, publishedLineupRows(team.copy(starters = team.starters.reversed())))
    }
    @Test fun changedFormationUsesThisMatchPositionsAndKeepsWideForwardsUpFront() {
        val team = lineup("4-3-3", listOf("Goalkeeper", "Left Back", "Center Left Defender", "Center Right Defender", "Right Back",
            "Left Midfielder", "Center Midfielder", "Right Midfielder", "Left Forward", "Forward", "Right Forward"))
        assertEquals(listOf(1, 4, 3, 3), publishedLineupRows(team)!!.map { it.size })
        // The same person's usual role is irrelevant: only this match's supplied position is used.
        val changed = team.copy(formation = "3-4-3", starters = team.starters.map {
            if (it.id == "1") it.copy(position = "Left Midfielder") else it
        })
        assertEquals(listOf(1, 3, 4, 3), publishedLineupRows(changed)!!.map { it.size })
        assertTrue(publishedLineupRows(changed)!![2].any { it.id == "1" })
    }
    @Test fun missingContradictoryDuplicateOrAmbiguousPositionsStayAList() {
        val team = lineup("3-4-2-1", leeds)
        assertNull(publishedLineupRows(team.copy(formation = null)))
        assertNull(publishedLineupRows(team.copy(starters = team.starters.dropLast(1))))
        assertNull(publishedLineupRows(team.copy(starters = team.starters.map { if (it.id == "9") it.copy(position = "Unknown") else it })))
        assertNull(publishedLineupRows(team.copy(starters = team.starters.map { if (it.id == "10") it.copy(id = "9") else it })))
        assertNull(publishedLineupRows(team.copy(starters = team.starters.map { if (it.id == "8") it.copy(position = "Center Forward") else it }
            .map { if (it.id == "9") it.copy(position = "Center Forward") else it })))
        val ambiguous = lineup("4-2-3-1", listOf("Goalkeeper") + List(4) { "Defender" } + List(5) { "Midfielder" } + "Forward")
        assertNull(publishedLineupRows(ambiguous))
    }
    @Test fun everyAvailableStarterStillGetsADiagramWhenPositionsOrFormationAreMissing() {
        val team = lineup("3-4-2-1", leeds)
        val unknown = team.copy(starters = team.starters.map { it.copy(position = "位置未提供") })
        assertNull(publishedLineupRows(unknown))
        assertEquals(listOf(1, 3, 4, 2, 1), lineupRows(unknown)!!.map { it.size })
        assertEquals(unknown.starters, lineupRows(unknown)!!.flatten())
        for (formation in listOf(null, "", "4-x-4-2")) {
            val missing = unknown.copy(formation = formation)
            assertEquals(unknown.starters, lineupRows(missing)!!.flatten())
        }
        val partial = unknown.copy(starters = unknown.starters.take(7), formation = null)
        assertEquals(partial.starters, lineupRows(partial)!!.flatten())
        assertNull(lineupRows(team.copy(starters = emptyList())))
        assertEquals(11, lineupRows(team.copy(starters = team.starters + team.starters.first()))!!.flatten().size)
    }

}
