package com.example.pitpitch.feature

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.R
import com.example.pitpitch.data.RankingEntry
import com.example.pitpitch.data.f1ConstructorDisplayName
import com.example.pitpitch.ui.*

private val countries = mapOf("Australia" to "au", "Australian" to "au", "China" to "cn", "Chinese" to "cn", "Japan" to "jp", "Japanese" to "jp",
    "Bahrain" to "bh", "Saudi Arabia" to "sa", "USA" to "us", "United States" to "us", "American" to "us", "Canada" to "ca", "Canadian" to "ca",
    "Monaco" to "mc", "Monegasque" to "mc", "Spain" to "es", "Spanish" to "es", "Austria" to "at", "Austrian" to "at", "UK" to "gb", "British" to "gb",
    "Belgium" to "be", "Belgian" to "be", "Hungary" to "hu", "Netherlands" to "nl", "Dutch" to "nl", "Italy" to "it", "Italian" to "it",
    "Azerbaijan" to "az", "Singapore" to "sg", "Mexico" to "mx", "Mexican" to "mx", "Brazil" to "br", "Brazilian" to "br", "Qatar" to "qa", "UAE" to "ae",
    "France" to "fr", "French" to "fr", "Germany" to "de", "German" to "de", "Finnish" to "fi", "Polish" to "pl", "New Zealander" to "nz", "Thai" to "th", "Danish" to "dk", "Argentine" to "ar", "Argentinian" to "ar")
private val flagResources = mapOf(
    "ae" to R.drawable.flag_ae,
    "ar" to R.drawable.flag_ar,
    "at" to R.drawable.flag_at,
    "au" to R.drawable.flag_au,
    "az" to R.drawable.flag_az,
    "be" to R.drawable.flag_be,
    "bh" to R.drawable.flag_bh,
    "br" to R.drawable.flag_br,
    "ca" to R.drawable.flag_ca,
    "cn" to R.drawable.flag_cn,
    "de" to R.drawable.flag_de,
    "dk" to R.drawable.flag_dk,
    "es" to R.drawable.flag_es,
    "fi" to R.drawable.flag_fi,
    "fr" to R.drawable.flag_fr,
    "gb" to R.drawable.flag_gb,
    "hu" to R.drawable.flag_hu,
    "it" to R.drawable.flag_it,
    "jp" to R.drawable.flag_jp,
    "mc" to R.drawable.flag_mc,
    "mx" to R.drawable.flag_mx,
    "nl" to R.drawable.flag_nl,
    "nz" to R.drawable.flag_nz,
    "pl" to R.drawable.flag_pl,
    "qa" to R.drawable.flag_qa,
    "sa" to R.drawable.flag_sa,
    "sg" to R.drawable.flag_sg,
    "th" to R.drawable.flag_th,
    "us" to R.drawable.flag_us
)
@Composable fun CountryFlag(country: String?, modifier: Modifier = Modifier) {
    val code = countries[country]
    val res = flagResources[code] ?: 0
    if (res != 0) Image(painterResource(res), country, modifier.width(26.dp).height(18.dp), contentScale = ContentScale.Fit)
    else Spacer(modifier.width(26.dp).height(18.dp))
}
@Composable fun F1TableHeader(result: Boolean = false, gridOnly: Boolean = false, constructors: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(PanelSurface).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (gridOnly) "发车" else "名次", Modifier.width(34.dp), color = Muted, fontSize = 11.sp)
        if (constructors) Spacer(Modifier.width(79.dp))
        Text(if (constructors) "车队" else "车手", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        if (result) Text("发车", Modifier.width(36.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (!gridOnly) Text(if (result) "本站积分" else "积分", Modifier.width(if (result) 58.dp else 44.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.End)
    }
}
@Composable fun F1StandingRow(entry: RankingEntry, season: Int, result: Boolean = false, gridOnly: Boolean = false, constructors: Boolean = false) {
    if (constructors) {
        ConstructorStandingRow(entry, season)
        return
    }
    val rank = if (gridOnly) entry.grid else entry.position
    val podium = (rank?.toIntOrNull() ?: 99) <= 3
    val accent = constructorColor(entry.constructorId, season)
    val background = if (rank == "1") RaceRed.copy(alpha = .04f) else Color.White
    val cardTag = "f1-${if (constructors) "constructor" else "driver"}-$season-${if (constructors) entry.constructorId else entry.driverId}"
    Column(Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp).testTag(cardTag)) {
        Row(Modifier.heightIn(min = 88.dp).padding(vertical = 8.dp).testTag("$cardTag-body"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rank ?: "—", Modifier.width(26.dp).testTag("$cardTag-rank"), fontSize = 16.sp,
                color = if (podium) RaceRed else Muted, fontWeight = if (podium) FontWeight.Bold else FontWeight.Normal)
            F1MediaImage(if (constructors) "logo" else "driver", if (constructors) entry.constructorId else entry.driverId,
                season, if (constructors) "${entry.name} 车队标识" else "${entry.name} 车手照片",
                Modifier.width(60.dp).height(64.dp).testTag("$cardTag-media"))
            StandingTeamStripe(accent, "$cardTag-accent")
            Column(Modifier.weight(1f).heightIn(min = 72.dp).testTag("$cardTag-identity"), verticalArrangement = Arrangement.Center) {
                Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (constructors) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End) {
                        F1MediaImage("car", entry.constructorId, season, "${entry.name} $season 赛季赛车",
                            Modifier.width(100.dp).height(36.dp))
                    }
                } else {
                    Text(f1ConstructorDisplayName(entry.team), color = Muted, fontSize = 11.sp)
                    CountryFlag(entry.nationality, Modifier.padding(top = 3.dp))
                    if (result) Text(entry.detail, color = Muted, fontSize = 10.sp)
                }
            }
            if (result) Text(entry.grid ?: "—", Modifier.width(32.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            if (!gridOnly) Text(entry.points, Modifier.width(if (result) 50.dp else standingPointsWidth()).testTag("$cardTag-points"),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = Line)
    }
}

@Composable private fun ConstructorStandingRow(entry: RankingEntry, season: Int) {
    val accent = constructorColor(entry.constructorId, season)
    val tag = "f1-constructor-$season-${entry.constructorId}"
    val podium = (entry.position?.toIntOrNull() ?: 99) <= 3
    Column(Modifier.fillMaxWidth().background(if (entry.position == "1") RaceRed.copy(alpha = .04f) else Color.White)
        .padding(horizontal = 4.dp).testTag(tag)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Match the driver list's columns; keep one shared height across every season.
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val pointsStyle = LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val pointsWidth = standingPointsWidth()
            val identityWidth = (maxWidth - 26.dp - 60.dp - 3.dp - pointsWidth - 32.dp).coerceAtLeast(1.dp)
            val carWidth = minOf(136.dp, identityWidth)
            val nameStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            val measuredNameHeight = remember(identityWidth, density.density, density.fontScale, nameStyle) {
                measurer.measure("Cadillac F1 Team", nameStyle,
                    constraints = Constraints(maxWidth = with(density) { identityWidth.roundToPx() })).size.height
            }
            val contentHeight = maxOf(72.dp, with(density) { measuredNameHeight.toDp() } + 2.dp + 34.dp)
            Row(Modifier.fillMaxWidth().height(contentHeight + 16.dp).padding(vertical = 8.dp).testTag("$tag-body"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.position ?: "—", Modifier.width(26.dp).testTag("$tag-rank"), fontSize = 16.sp,
                    maxLines = 1, softWrap = false, fontWeight = if (podium) FontWeight.Bold else FontWeight.Normal,
                    color = if (podium) RaceRed else Muted)
                Box(Modifier.width(60.dp).height(64.dp).testTag("$tag-logo")) {
                    F1MediaImage("logo", entry.constructorId, season, "${entry.name} 车队标识", Modifier.fillMaxSize())
                }
                StandingTeamStripe(accent, "$tag-accent")
                Column(Modifier.weight(1f).height(contentHeight).testTag("$tag-identity"),
                    horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.fillMaxWidth().testTag("$tag-name-slot")) {
                        Text(f1ConstructorDisplayName(entry.name), Modifier.fillMaxWidth().testTag("$tag-name"), style = nameStyle, textAlign = TextAlign.Start)
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.width(carWidth).height(34.dp).testTag("$tag-car-slot")) {
                        F1MediaImage("car", entry.constructorId, season, "${entry.name} $season 赛季赛车", Modifier.fillMaxSize())
                    }
                }
                Text(entry.points, Modifier.width(pointsWidth).testTag("$tag-points"), style = pointsStyle,
                    maxLines = 1, softWrap = false, textAlign = TextAlign.End)
            }
        }
        HorizontalDivider(color = Line)
    }
}


@Composable private fun standingPointsWidth(): androidx.compose.ui.unit.Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
    return remember(density.density, density.fontScale, style) {
        maxOf(44.dp, with(density) { measurer.measure("999.5", style).size.width.toDp() } + 2.dp)
    }
}

@Composable private fun StandingTeamStripe(color: Color, tag: String) {
    // A faint keyline keeps the historically white team colours visible on white.
    val pale = color.red > .95f && color.green > .95f && color.blue > .95f
    Box(Modifier.width(3.dp).height(28.dp).background(color)
        .then(if (pale) Modifier.border(.5.dp, Line) else Modifier).testTag(tag))
}
