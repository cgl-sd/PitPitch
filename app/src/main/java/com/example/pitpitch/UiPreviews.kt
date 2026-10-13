package com.example.pitpitch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.example.pitpitch.ui.SportsColors
import com.example.pitpitch.ui.components.*
@Preview(name = "白色底部导航", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable fun BottomBarPreview() {
    MaterialTheme(colorScheme = SportsColors) {
        BottomBar(listOf(NavItem("首页", Icons.Outlined.Home), NavItem("足球", Icons.Outlined.SportsSoccer), NavItem("F1", Icons.Outlined.SportsScore)), 0, {})
    }
}
