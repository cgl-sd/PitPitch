package com.example.pitpitch.data

import org.junit.Assert.*
import org.junit.Test

class MatchPresentationTest {
    @Test fun clockUsesProviderSecondsAndDoesNotInventAddedTimeSeconds() {
        assertEquals("67:23", providerMatchClock("67'", 4043.0))
        assertEquals("45+2:15", providerMatchClock("45+2:15", 2700.0))
        assertEquals("45+2′", providerMatchClock("45'+2'", 2700.0))
        assertEquals("45+2:15", providerMatchClock("45'+2'", 2835.0))
        assertEquals("67′", providerMatchClock("67′", null))
        assertNull(providerMatchClock(null, null))
    }
    @Test fun scoreStatusShowsClockHalftimeOrFinalWithoutInProgressCaption() {
        val match = sampleMatches.first().copy(status = "进行中", liveDetail = "67:23")
        assertEquals("67:23", matchScoreStatus(match))
        assertEquals("中场", matchScoreStatus(match.copy(liveDetail = "HT")))
        assertEquals("完场", matchScoreStatus(match.copy(status = "完场")))
        assertEquals("—", matchScoreStatus(match.copy(liveDetail = null)))
    }
    @Test fun compactStatisticsKeepPenaltyAndOriginalValuesWithoutFillingMissingRows() {
        val original = listOf(MatchStatistic("射门转化率", "0.6", "0.3"), MatchStatistic("点球进球", "0", "1"),
            MatchStatistic("射门", "7", "16"), MatchStatistic("控球率", "48.2", null))
        assertEquals(listOf(original[3], original[2], original[1]), compactMatchStatistics(original))
        assertEquals(4, original.size)
    }
}
