package com.example.pitpitch.ui

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val Background = Color.White
val PanelSurface = Color(0xFFF5F6F8)
val SurfaceHighlight = Color(0xFFF1F3F6)
val Muted = Color(0xFF526176)
val FootballGreen = Color(0xFF289EE8)
val FootballAccent = Color(0xFF15965B)
val RaceRed = Color(0xFFE10600)
val Line = Color(0xFFE2E8F0)
val PrimaryText = Color(0xFF20252D)
val SportsColors = lightColorScheme(
    primary = FootballGreen, secondary = RaceRed,
    background = Background, surface = Background,
    surfaceVariant = PanelSurface, onSurfaceVariant = Muted,
    onSurface = PrimaryText, onBackground = PrimaryText, onPrimary = Color.White,
    outline = Muted, outlineVariant = Line,
)
