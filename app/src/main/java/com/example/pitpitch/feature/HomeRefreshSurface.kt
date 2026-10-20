package com.example.pitpitch.feature

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Both gestures and a programmatic refresh move the content, then settle it back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HomeRefreshSurface(refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit) {
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(refreshing, onRefresh, state = pull,
        modifier = Modifier.fillMaxSize().clipToBounds().testTag("home-refresh"), indicator = {}) {
        Box(Modifier.fillMaxSize().offset { IntOffset(0, (64.dp.toPx() * pull.distanceFraction.coerceAtMost(1.5f)).roundToInt()) }
            .testTag("home-refresh-content")) { content() }
    }
}
