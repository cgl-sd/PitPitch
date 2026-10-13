package com.example.pitpitch.feature

import com.example.pitpitch.ui.components.footballTeamName
import com.example.pitpitch.ui.components.UpdateSettingsRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.ui.*

@Composable fun SettingsScreen(state: SportsViewModel.State, homeCompetition: (String, Boolean) -> Unit, selectTeam: () -> Unit, about: () -> Unit) {
    Page {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("settings-select-team").clickable(onClick = selectTeam),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("选择主队", fontSize = 15.sp)
                    Text(state.homeTeam?.let { footballTeamName(it.id, it.name) } ?: "未选择", color = Muted, fontSize = 12.sp)
                }
                state.homeTeam?.let { com.example.pitpitch.ui.components.TeamBadge(footballTeamName(it.id, it.name), it.id, it.badgeUrl, size = 32.dp) }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Muted)
            }
            HorizontalDivider(color = Line)
        }
        item { Heading("首页显示联赛", "选择要在首页展示的赛程") }
        items(footballCompetitions.map { it.id to it.title } + ("f1" to "F1"), key = { "home-choice-${it.first}" }) { (id, title) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 15.sp)
                Switch(id !in state.hiddenHomeCompetitions, { homeCompetition(id, it) }, Modifier.testTag("home-visible-$id"))
            }
            HorizontalDivider(color = Line)
        }
        item { UpdateSettingsRow() }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open-about").clickable(onClick = about),
                verticalAlignment = Alignment.CenterVertically) {
                Text("隐私和关于", Modifier.weight(1f), fontSize = 15.sp)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Muted)
            }
        }
    }
}

@Composable fun AboutScreen() {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—" }
    Page {
        item { Panel {
            Row(Modifier.fillMaxWidth().testTag("app-version"), verticalAlignment = Alignment.CenterVertically) {
                Text("应用版本", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(version, color = Muted)
            }
        } }
        item { Heading("许可与署名") }
        item { Column(Modifier.testTag("settings-data-sources"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ESPN · Jolpica · Formula 1 · 各图片权利人", color = Muted, fontSize = 12.sp)
            Text("Wikidata · OpenFootball（CC0）", color = Muted, fontSize = 12.sp)
            Text("FixtureDownload · 央视 · 曼联中文官网 · FlagCDN", color = Muted, fontSize = 12.sp)
            Text("bacinger/f1-circuits · MIT\n© 2019–2025 Tomislav Bacinger", color = Muted, fontSize = 12.sp)
            Text("Wikimedia Commons 赛道轮廓 · CC BY-SA 3.0/4.0 · CC0/公有领域\n原作者、改编与许可原文随图片保留", color = Muted, fontSize = 12.sp)
        } }
    }
}
