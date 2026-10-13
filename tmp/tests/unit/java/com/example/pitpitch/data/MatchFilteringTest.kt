package com.example.pitpitch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchFilteringTest {
    @Test fun allMatchesShownByDefault() {
        assertEquals(3, filterMatches(sampleMatches, "全部", 0, "").size)
    }

    @Test fun leagueAndStateCanBeCombined() {
        val result = filterMatches(sampleMatches, "西班牙", 1, "")
        assertEquals(listOf("fb-demo-3"), result.map(FootballMatch::id))
    }

    @Test fun trimmedSearchIsCaseInsensitive() {
        val result = filterMatches(sampleMatches, "全部", 0, "  fc  ")
        assertEquals(listOf("fb-demo-1"), result.map(FootballMatch::id))
    }

    @Test fun unknownSearchGivesEmptyList() {
        assertEquals(0, filterMatches(sampleMatches, "全部", 0, "nonexistent").size)
    }
}
