package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.F1Race
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.feature.HomeScreen
import com.example.pitpitch.ui.SportsColors
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HomeF1IsolationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun historicalSelectionDoesNotChangeCurrentYearPreviewAndEndedSeasonHidesIt() {
        val today = LocalDate.now()
        val year = today.year
        val current = F1Race(year, 12, "Singapore Grand Prix", "Marina Bay", today.plusDays(2).toString(), "12:00:00Z", "Singapore")
        val old = current.copy(season = year - 1, name = "Old Grand Prix", date = today.minusYears(1).toString())
        val screen = mutableStateOf(SportsViewModel.State(homeReady = true, ready = true,
            season = year, hiddenHomeCompetitions = footballCompetitions.map { it.id }.toSet(),
            calendars = mapOf(year to listOf(current), year - 1 to listOf(old))))
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            HomeScreen(screen.value, ZoneId.systemDefault(), {}, {}, {}, {}, {})
        } }
        rule.onNodeWithTag("f1-weekend").assertExists()
        rule.onNodeWithText("新加坡大奖赛").assertExists()
        rule.runOnUiThread { screen.value = screen.value.copy(season = year - 1) }
        rule.onNodeWithText("新加坡大奖赛").assertExists()
        rule.onNodeWithText("暂无 F1 后续赛程").assertDoesNotExist()
        rule.runOnUiThread { screen.value = screen.value.copy(calendars = screen.value.calendars +
            (year to listOf(current.copy(date = today.minusDays(2).toString())))) }
        rule.onNodeWithTag("f1-weekend").assertDoesNotExist()
        rule.onNodeWithText("暂无 F1 后续赛程").assertDoesNotExist()
    }
}
