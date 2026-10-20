package com.example.pitpitch.feature

import com.example.pitpitch.ui.components.footballTeamName
import com.example.pitpitch.ui.components.UpdateSettingsRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.pitpitch.data.footballCompetitions
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.ui.*

@Composable fun SettingsScreen(state: SportsViewModel.State, homeCompetition: (String, Boolean) -> Unit, selectTeam: () -> Unit, about: () -> Unit) {
    Page(spacing = 16.dp) {
        item { SettingsGroup("settings-team-group") {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("settings-select-team").clickable(onClick = selectTeam),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("选择主队", fontSize = 15.sp)
                    Text(state.homeTeam?.let { footballTeamName(it.id, it.name) } ?: "未选择", color = Muted, fontSize = 12.sp)
                }
                state.homeTeam?.let { com.example.pitpitch.ui.components.TeamBadge(footballTeamName(it.id, it.name), it.id, it.badgeUrl, size = 32.dp) }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Muted)
            }
        } }
        item { SettingsGroup("settings-home-group") {
            Text("首页展示", Modifier.padding(top = 12.dp, bottom = 4.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val choices = footballCompetitions.map { it.id to it.title } + ("f1" to "F1")
            choices.forEachIndexed { index, (id, title) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 15.sp)
                    Switch(id !in state.hiddenHomeCompetitions, { homeCompetition(id, it) }, Modifier.testTag("home-visible-$id"))
                }
                if (index < choices.lastIndex) HorizontalDivider(color = Line)
            }
        } }
        item { SettingsGroup("settings-app-group") {
            UpdateSettingsRow()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open-about").clickable(onClick = about),
                verticalAlignment = Alignment.CenterVertically) {
                Text("隐私和关于", Modifier.weight(1f), fontSize = 15.sp)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Muted)
            }
        } }
    }
}

@Composable private fun SettingsGroup(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(16.dp),
        color = Background, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(horizontal = 12.dp), content = content)
    }
}

@Composable fun AboutScreen() {
    Page(spacing = 20.dp) {
        item { AboutSection("数据来源", "settings-data-sources", listOf(
            "足球赛事" to "ESPN",
            "F1 赛事" to "Jolpica",
            "名称与轮次" to "Wikidata、OpenFootball、FixtureDownload、央视、曼联中文官网",
            "图片与赛道" to "Formula 1、FlagCDN、Wikimedia Commons、bacinger/f1-circuits 及各图片权利人",
        )) }
        item { AboutSection("开源协议", "settings-open-source", listOf(
            "CC0 / 公有领域" to "Wikidata、OpenFootball，以及采用此许可的 Wikimedia Commons 资源。",
            "MIT" to "bacinger/f1-circuits\n© 2019–2025 Tomislav Bacinger",
            "CC BY-SA 3.0 / 4.0" to "Wikimedia Commons 赛道轮廓；原作者、来源、改编说明及许可原文随资源保留。",
            "SIL Open Font License 1.1" to "Roboto 启动字标字形\n© 2011 The Roboto Project Authors",
            "其他图片与商标" to "版权归各权利人，按各自许可使用；公开访问不代表开源授权。",
        )) }
    }
}

@Composable private fun AboutSection(title: String, tag: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        rows.forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(value, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
