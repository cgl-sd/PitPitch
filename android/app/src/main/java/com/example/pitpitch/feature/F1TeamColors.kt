package com.example.pitpitch.feature

import androidx.compose.ui.graphics.Color
import com.example.pitpitch.ui.Muted

// 2020–2025: FastF1 constants.json, colors.official (not its plotting palette).
// https://github.com/theOehrly/Fast-F1/blob/main/fastf1/plotting/constants.json
// 2026: https://www.formula1.com/en/teams --f1-team-colour, checked 2026-10-20.
// Keep historical identities and colours separate from current team branding.
private val teamColorsBySeason = mapOf(
    2020 to mapOf(
        "alfa" to 0xFF9B0000,
        "alphatauri" to 0xFFFFFFFF,
        "ferrari" to 0xFFDC0000,
        "haas" to 0xFF787878,
        "mclaren" to 0xFFFF8700,
        "mercedes" to 0xFF00D2BE,
        "racing_point" to 0xFFF596C8,
        "red_bull" to 0xFF1E41FF,
        "renault" to 0xFFFFF500,
        "williams" to 0xFF0082FA
    ),
    2021 to mapOf(
        "alfa" to 0xFF900000,
        "alphatauri" to 0xFF2B4562,
        "alpine" to 0xFF0090FF,
        "aston_martin" to 0xFF006F62,
        "ferrari" to 0xFFDC0004,
        "haas" to 0xFFFFFFFF,
        "mclaren" to 0xFFFF9800,
        "mercedes" to 0xFF00D2BE,
        "red_bull" to 0xFF0600EF,
        "williams" to 0xFF005AFF
    ),
    2022 to mapOf(
        "alfa" to 0xFFB12039,
        "alphatauri" to 0xFF4E7C9B,
        "alpine" to 0xFF2293D1,
        "aston_martin" to 0xFF2D826D,
        "ferrari" to 0xFFED1C24,
        "haas" to 0xFFB6BABD,
        "mclaren" to 0xFFF58020,
        "mercedes" to 0xFF6CD3BF,
        "red_bull" to 0xFF1E5BC6,
        "williams" to 0xFF37BEDD
    ),
    2023 to mapOf(
        "alfa" to 0xFFC92D4B,
        "alphatauri" to 0xFF5E8FAA,
        "alpine" to 0xFF2293D1,
        "aston_martin" to 0xFF358C75,
        "ferrari" to 0xFFF91536,
        "haas" to 0xFFB6BABD,
        "mclaren" to 0xFFF58020,
        "mercedes" to 0xFF6CD3BF,
        "red_bull" to 0xFF3671C6,
        "williams" to 0xFF37BEDD
    ),
    2024 to mapOf(
        "alpine" to 0xFF0093CC,
        "aston_martin" to 0xFF229971,
        "ferrari" to 0xFFE8002D,
        "haas" to 0xFFB6BABD,
        "mclaren" to 0xFFFF8000,
        "mercedes" to 0xFF27F4D2,
        "rb" to 0xFF6692FF,
        "red_bull" to 0xFF3671C6,
        "sauber" to 0xFF52E252,
        "williams" to 0xFF64C4FF
    ),
    2025 to mapOf(
        "alpine" to 0xFF0093CC,
        "aston_martin" to 0xFF229971,
        "ferrari" to 0xFFE80020,
        "haas" to 0xFFB6BABD,
        "mclaren" to 0xFFFF8000,
        "mercedes" to 0xFF27F4D2,
        "rb" to 0xFF6692FF,
        "red_bull" to 0xFF3671C6,
        "sauber" to 0xFF52E252,
        "williams" to 0xFF64C4FF
    ),
    2026 to mapOf(
        "mercedes" to 0xFF27F4D2,
        "ferrari" to 0xFFE8002D,
        "mclaren" to 0xFFFF8000,
        "red_bull" to 0xFF3671C6,
        "rb" to 0xFF6692FF,
        "alpine" to 0xFF00A1E8,
        "haas" to 0xFFDEE1E2,
        "audi" to 0xFFFF2D00,
        "williams" to 0xFF1868DB,
        "aston_martin" to 0xFF229971,
        "cadillac" to 0xFFAAAAAD
    )
)

internal fun constructorColor(id: String?, season: Int): Color =
    teamColorsBySeason[season]?.get(id)?.let { Color(it) } ?: Muted
