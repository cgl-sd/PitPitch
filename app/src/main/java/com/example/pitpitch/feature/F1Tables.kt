package com.example.pitpitch.feature

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.R
import com.example.pitpitch.data.RankingEntry
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
private fun teamColor(team: String): Color = when {
    team.contains("Ferrari", true) -> Color(0xFFE10600)
    team.contains("McLaren", true) -> Color(0xFFFF8700)
    team.contains("Mercedes", true) -> Color(0xFF00A99C)
    team.contains("Aston", true) -> Color(0xFF006F62)
    team.contains("Alpine", true) -> Color(0xFF329BDF)
    team.contains("Red Bull", true) -> Color(0xFF233CAE)
    team.contains("Sauber", true) -> Color(0xFF328C45)
    team.contains("Williams", true) -> Color(0xFF2374DB)
    else -> Muted
}
@Composable fun F1TableHeader(result: Boolean = false, gridOnly: Boolean = false, constructors: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(PanelSurface).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (gridOnly) "发车" else "名次", Modifier.width(34.dp), color = Muted, fontSize = 11.sp)
        Text(if (constructors) "车队" else "车手", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        if (result) Text("发车", Modifier.width(36.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (!gridOnly) Text(if (result) "本站积分" else "积分", Modifier.width(if (result) 58.dp else 44.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.End)
    }
}
@Composable fun F1StandingRow(entry: RankingEntry, season: Int, result: Boolean = false, gridOnly: Boolean = false, constructors: Boolean = false) {
    if (constructors) {
        ConstructorStandingCard(entry, season)
        return
    }
    val rank = if (gridOnly) entry.grid else entry.position
    val podium = (rank?.toIntOrNull() ?: 99) <= 3
    val accent = if (constructors) constructorColor(entry.constructorId, season) else entry.constructorId?.let { constructorColor(it, season) } ?: teamColor(entry.team)
    val background = if (constructors) androidx.compose.ui.graphics.lerp(Color.White, accent, .09f)
        else if (rank == "1") RaceRed.copy(alpha = .04f) else Color.White
    val cardTag = "f1-${if (constructors) "constructor" else "driver"}-$season-${if (constructors) entry.constructorId else entry.driverId}"
    Column(Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp).testTag(cardTag)) {
        Row(Modifier.heightIn(min = 88.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rank ?: "—", Modifier.width(26.dp), fontSize = 16.sp,
                color = if (podium) RaceRed else Muted, fontWeight = if (podium) FontWeight.Bold else FontWeight.Normal)
            F1MediaImage(if (constructors) "logo" else "driver", if (constructors) entry.constructorId else entry.driverId,
                season, if (constructors) "${entry.name} 车队标识" else "${entry.name} 车手照片",
                Modifier.width(60.dp).height(64.dp))
            Box(Modifier.width(3.dp).height(28.dp).background(accent))
            Column(Modifier.weight(1f).heightIn(min = 72.dp), verticalArrangement = Arrangement.Center) {
                Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (constructors) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End) {
                        F1MediaImage("car", entry.constructorId, season, "${entry.name} $season 赛季赛车",
                            Modifier.width(100.dp).height(36.dp))
                    }
                } else {
                    Text(entry.team, color = Muted, fontSize = 11.sp)
                    CountryFlag(entry.nationality, Modifier.padding(top = 3.dp))
                    if (result) Text(entry.detail, color = Muted, fontSize = 10.sp)
                }
            }
            if (result) Text(entry.grid ?: "—", Modifier.width(32.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            if (!gridOnly) Text(entry.points, Modifier.width(if (result) 50.dp else 44.dp).testTag("$cardTag-points"),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = Line)
    }
}

@Composable private fun ConstructorStandingCard(entry: RankingEntry, season: Int) {
    val accent = constructorColor(entry.constructorId, season)
    val tag = "f1-constructor-$season-${entry.constructorId}"
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.lerp(Color.White, accent, .06f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Row(Modifier.weight(1f).heightIn(min = 108.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.position ?: "—", Modifier.width(24.dp).testTag("$tag-rank"), fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, color = PrimaryText, textAlign = TextAlign.Center)
                Column(Modifier.weight(1f).testTag("$tag-identity"), horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    F1MediaImage("logo", entry.constructorId, season, "${entry.name} 车队标识", Modifier.width(68.dp).height(38.dp))
                    Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
                }
                Column(Modifier.width(136.dp), horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.points, Modifier.testTag("$tag-points"), fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    F1MediaImage("car", entry.constructorId, season, "${entry.name} $season 赛季赛车",
                        Modifier.fillMaxWidth().height(34.dp))
                }
            }
        }
    }
}

internal fun constructorColor(id: String?, season: Int): Color = when (id) {
    "mercedes" -> Color(0xFF27F4D2)
    "ferrari" -> Color(0xFFE8002D)
    "mclaren" -> Color(0xFFFF8000)
    "red_bull" -> Color(0xFF3671C6)
    "rb" -> Color(0xFF6692FF)
    "alphatauri" -> Color(0xFF344768)
    "alpine" -> Color(0xFF00A1E8)
    "renault" -> Color(0xFFFFCF00)
    "haas" -> Color(0xFF667175)
    "audi" -> Color(0xFFFF2D00)
    "williams" -> Color(0xFF1868DB)
    "aston_martin" -> Color(0xFF229971)
    "racing_point" -> Color(0xFFEF6AA8)
    "alfa" -> Color(0xFF9B1B32)
    "sauber" -> if (season >= 2024) Color(0xFF328C45) else Color(0xFF9B1B32)
    "cadillac" -> Color(0xFF58585B)
    else -> Muted
}
