package com.example.pitpitch.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.FootballTeam
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.pitpitch.ui.*

data class NavItem(val label: String, val icon: ImageVector)
@Composable fun BottomBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, team: FootballTeam? = null, onTeam: () -> Unit = {}, onHomeRefresh: (() -> Unit)? = null) {
    val teamLabel = team?.let { footballTeamName(it.id, it.name) }
    Row(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(Modifier.weight(1f).height(56.dp).testTag("nav-group"), shape = RoundedCornerShape(50), color = SurfaceHighlight) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { index, item ->
                    val active = selected == index
                    val accent = when (index) { 1 -> FootballAccent; 2 -> RaceRed; else -> FootballGreen }
                    val tint = if (active) accent else Muted
                    Column(Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(if (active) accent.copy(alpha = .08f) else Color.Transparent, RoundedCornerShape(50))
                        .testTag("nav-$index").semantics {
                            if (index == 0 && onHomeRefresh != null) customActions = listOf(CustomAccessibilityAction("刷新首页") { onHomeRefresh(); true })
                        }.selectable(selected = active, interactionSource = remember { MutableInteractionSource() },
                            indication = null, role = Role.Tab, onClick = { onSelect(index) }),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        val iconModifier = if (index == 2) Modifier.size(32.dp).drawWithContent {
                            rotate(12f) { this@drawWithContent.drawContent() }
                        } else Modifier.size(32.dp)
                        Icon(item.icon, contentDescription = item.label, iconModifier, tint = tint)
                    }
                }
            }
        }
        Box(Modifier.size(56.dp).testTag("nav-team").semantics {
            contentDescription = team?.let { "主队：$teamLabel，查看球队详情" } ?: "选择主队"
        }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onTeam),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(48.dp).testTag("home-team-artwork")) {
                TeamBadge(teamLabel ?: "未选择", team?.id, team?.badgeUrl, Modifier.clearAndSetSemantics { }, size = 48.dp)
            }
        }
    }
}
@Composable fun Segments(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, accent: Color = FootballAccent, pill: Boolean = true, itemTagPrefix: String? = null) {
    val trackShape = if (pill) RoundedCornerShape(50) else RoundedCornerShape(8.dp)
    val selectionShape = if (pill) RoundedCornerShape(50) else RoundedCornerShape(6.dp)
    Box(modifier.fillMaxWidth().then(if (pill) Modifier.clip(trackShape).border(1.dp, Line, trackShape) else Modifier)) {
        Box(Modifier.matchParentSize().clip(trackShape).background(PanelSurface))
        Row(Modifier.fillMaxWidth().padding(horizontal = if (pill) 0.dp else 3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(if (pill) 0.dp else 2.dp)) {
            items.forEachIndexed { index, title ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .then(itemTagPrefix?.let { Modifier.testTag("$it-$index") } ?: Modifier)
                    .clip(selectionShape)
                    .background(if (selected == index) Color.White else Color.Transparent)
                    .selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    SegmentLabel(title, if (selected == index) accent else Muted,
                        if (selected == index) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
                }
            }
        }
    }
}
/** Phase and mode labels stay complete under large fonts: shrink to the segment width, never clip or ellipsize. */
@Composable private fun SegmentLabel(text: String, color: Color, weight: androidx.compose.ui.text.font.FontWeight) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val style = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = weight, color = color)
        val width = measurer.measure(text, style, softWrap = false).size.width
        val fraction = if (width <= 0) 1f else (constraints.maxWidth.toFloat() / (width + 2)).coerceAtMost(1f)
        Text(text, Modifier.fillMaxWidth(), style = style.copy(fontSize = (13f * fraction).sp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, softWrap = false)
    }
}
@Composable fun Filters(items: List<String>, selected: String, onSelect: (String) -> Unit, accent: Color = FootballAccent, label: (String) -> String = { it }, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { title -> Column(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .selectable(selected == title, role = Role.Tab, onClick = { onSelect(title) }).padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Text(label(title), maxLines = 1, fontSize = 14.sp, lineHeight = 20.sp, color = if (title == selected) accent else Muted,
                fontWeight = if (title == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
            Box(Modifier.width(22.dp).height(2.dp).background(if (title == selected) accent else Color.Transparent, RoundedCornerShape(2.dp)))
        } }
    }
}
