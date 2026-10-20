package com.example.pitpitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.feature.SettingsScreen
import com.example.pitpitch.feature.AboutScreen
import com.example.pitpitch.ui.SportsColors
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsPresentationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aboutShowsTwoConsistentSectionsAndPreservesResourceCreditsWithoutVersion() {
        rule.setContent { MaterialTheme(colorScheme = SportsColors) { AboutScreen() } }
        rule.onNodeWithText("数据来源").assertIsDisplayed()
        rule.onNodeWithTag("app-version").assertDoesNotExist()
        rule.onNodeWithText("应用版本").assertDoesNotExist()
        rule.onNodeWithText("ESPN").assertExists()
        rule.onNodeWithText("Jolpica").assertExists()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("settings-open-source"))
        for (label in listOf("开源协议", "CC0 / 公有领域", "MIT", "CC BY-SA 3.0 / 4.0", "SIL Open Font License 1.1", "其他图片与商标"))
            rule.onNodeWithText(label).assertExists()
        rule.onNodeWithText("Tomislav Bacinger", substring = true).assertExists()
        rule.onNodeWithText("The Roboto Project Authors", substring = true).assertExists()
    }

    @Test fun threeGroupsKeepActionsAndSwitchesWithoutExplanatorySubtitles() {
        val state = mutableStateOf(SportsViewModel.State())
        var teamOpened = false
        var aboutOpened = false
        rule.setContent { MaterialTheme(colorScheme = SportsColors) {
            SettingsScreen(state.value, { id, visible ->
                state.value = state.value.copy(hiddenHomeCompetitions =
                    if (visible) state.value.hiddenHomeCompetitions - id else state.value.hiddenHomeCompetitions + id)
            }, { teamOpened = true }, { aboutOpened = true })
        } }
        rule.onNodeWithTag("settings-team-group").assertIsDisplayed()
        rule.onNodeWithTag("settings-home-group").assertIsDisplayed()
        rule.onNodeWithText("首页展示").assertExists()
        rule.onNodeWithText("选择要在首页展示的赛程").assertDoesNotExist()
        rule.onAllNodesWithText("GitHub", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("settings-select-team").performClick()
        rule.runOnIdle { assertTrue(teamOpened) }
        rule.onNodeWithTag("home-visible-eng.1").assertIsOn().performClick().assertIsOff()
        rule.onNodeWithTag("home-visible-eng.1").performClick().assertIsOn()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("settings-app-group"))
        rule.onNodeWithTag("settings-check-update").assertIsDisplayed()
        rule.onNodeWithTag("open-about").performClick()
        rule.runOnIdle { assertTrue(aboutOpened) }
        rule.onAllNodesWithText("从 GitHub", substring = true).assertCountEquals(0)
    }
}
