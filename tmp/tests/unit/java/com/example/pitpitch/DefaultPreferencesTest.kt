package com.example.pitpitch

import androidx.datastore.preferences.core.*
import com.example.pitpitch.data.local.*
import org.junit.Assert.*
import org.junit.Test

class DefaultPreferencesTest {
    @Test fun unsetPreferencesShowPremierLeagueChampionsLeagueAndF1WithManchesterUnited() {
        val prefs = emptyPreferences()
        assertEquals(setOf("esp.1", "ita.1", "ger.1", "fra.1", "uefa.europa"), storedHiddenHomeCompetitions(prefs))
        assertEquals(1_000_360, storedHomeTeam(prefs)!!.id)
        assertEquals("Manchester United", storedHomeTeam(prefs)!!.name)
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/360.png", storedHomeTeam(prefs)!!.badgeUrl)
    }
    @Test fun explicitChoicesIncludingEmptySetsAndClearedTeamOverrideDefaults() {
        val prefs = mutablePreferencesOf(stringSetPreferencesKey("hidden_home_competitions") to emptySet<String>(), intPreferencesKey("home_team_id") to 1_000_359, stringPreferencesKey("home_team_name") to "Arsenal")
        assertTrue(storedHiddenHomeCompetitions(prefs).isEmpty())
        assertEquals("Arsenal", storedHomeTeam(prefs)!!.name)
        prefs.remove(intPreferencesKey("home_team_id")); prefs.remove(stringPreferencesKey("home_team_name"))
        prefs[booleanPreferencesKey("home_team_choice_initialized")] = true
        assertNull(storedHomeTeam(prefs))
        prefs[stringSetPreferencesKey("hidden_home_competitions")] = setOf("eng.1", "f1")
        assertEquals(setOf("eng.1", "f1"), storedHiddenHomeCompetitions(prefs))
    }
}
