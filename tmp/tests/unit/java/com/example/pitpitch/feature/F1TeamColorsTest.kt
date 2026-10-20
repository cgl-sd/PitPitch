package com.example.pitpitch.feature

import androidx.compose.ui.graphics.Color
import com.example.pitpitch.ui.Muted
import org.junit.Assert.*
import org.junit.Test

class F1TeamColorsTest {
    @Test fun usesPrimaryTeamColorsInsteadOfAccessibleTextColors() {
        assertEquals(Color(0xFFDEE1E2), constructorColor("haas", 2026))
        assertEquals(Color(0xFFAAAAAD), constructorColor("cadillac", 2026))
        assertEquals(Color(0xFF52E252), constructorColor("sauber", 2024))
        assertEquals(Color(0xFF64C4FF), constructorColor("williams", 2025))
    }

    @Test fun preservesHistoricalIdentityAndSeasonSpecificColors() {
        assertEquals(Color(0xFF00D2BE), constructorColor("mercedes", 2020))
        assertEquals(Color(0xFF6CD3BF), constructorColor("mercedes", 2022))
        assertEquals(Color(0xFF27F4D2), constructorColor("mercedes", 2026))
        assertEquals(Color(0xFF9B0000), constructorColor("alfa", 2020))
        assertEquals(Muted, constructorColor("sauber", 2020))
        assertEquals(Muted, constructorColor(null, 2026))
    }
}
