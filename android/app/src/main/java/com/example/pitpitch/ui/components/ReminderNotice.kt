package com.example.pitpitch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val LocalReminderNotice = staticCompositionLocalOf<(String) -> Unit> { {} }

/** A single latest-message host prevents queued feedback after repeated booking taps. */
@Composable fun ReminderNoticeHost(content: @Composable () -> Unit) {
    var message by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        if (revision > 0) {
            visible = true
            delay(if (message == "已预约" || message == "已取消") 1400 else 3000)
            visible = false
        }
    }
    CompositionLocalProvider(LocalReminderNotice provides { text -> message = text; revision++ }) {
        Box(Modifier.fillMaxSize()) {
            content()
            AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp),
                enter = fadeIn(tween(70)), exit = fadeOut(tween(120))) {
                Box(Modifier.padding(horizontal = 28.dp).clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE8EDF2).copy(alpha = .86f))
                    .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 22.dp, vertical = 11.dp)) {
                    Text(message, color = Color(0xFF20252D), fontSize = 14.sp)
                }
            }
        }
    }
}
