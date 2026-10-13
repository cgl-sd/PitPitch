package com.example.pitpitch.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.Text
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import com.example.pitpitch.ui.RaceRed
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.pitpitch.R
import com.example.pitpitch.ui.PrimaryText

private val SportsBrandHeight = 26.dp

/** Source artwork from formula1.com; retained proportions, never redrawn or generated. */
@Composable fun F1Brand(modifier: Modifier = Modifier) {
    val painter = painterResource(R.drawable.ic_f1_wordmark)
    val aspectRatio = painter.intrinsicSize.width / painter.intrinsicSize.height
    Image(painter, "F1 红色标识",
        modifier.width(SportsBrandHeight * aspectRatio).height(SportsBrandHeight).testTag("f1-brand"),
        contentScale = ContentScale.Fit)
}
@Composable fun FootballBrand() {
    Icon(Icons.Outlined.SportsSoccer, "足球", Modifier.size(SportsBrandHeight).testTag("football-brand"), tint = PrimaryText)
}

/** ESPN's default artwork, bundled so Home league marks also work offline. */
@Composable fun CompetitionLogo(id: String?, title: String) {
    val resource = when (id) {
        "eng.1" -> R.drawable.league_premier
        "esp.1" -> R.drawable.league_laliga
        "ita.1" -> R.drawable.league_serie_a
        "ger.1" -> R.drawable.league_bundesliga
        "fra.1" -> R.drawable.league_ligue_1
        "uefa.champions" -> R.drawable.league_champions
        "uefa.europa" -> R.drawable.league_europa
        else -> null
    }
    if (resource != null) Image(painterResource(resource), "$title 标识", Modifier.size(30.dp), contentScale = ContentScale.Fit)
    else Icon(Icons.Outlined.SportsSoccer, "$title 标识未提供", Modifier.size(30.dp), tint = PrimaryText)
}

/** Native two-color wordmark; text remains crisp at every display/font scale. */
@Composable fun PitPitchWordmark() {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = RaceRed)) { append("Pit") }
        withStyle(SpanStyle(color = Color(0xFF008A4B))) { append("Pitch") }
    }, modifier = Modifier.testTag("pitpitch-wordmark").padding(end = 6.dp),
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black,
        fontSize = 28.sp, letterSpacing = (-1).sp, maxLines = 1,
        style = androidx.compose.ui.text.TextStyle(textGeometricTransform = TextGeometricTransform(scaleX = .94f, skewX = -.18f)))
}
