package com.example.pitpitch.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.F1Race
import com.example.pitpitch.data.f1SeasonProgress
import com.example.pitpitch.ui.*

@Composable fun F1SeasonProgressBar(season: Int, races: List<F1Race>, standingsRound: String?) {
    val progress = f1SeasonProgress(season, races, standingsRound)
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp).testTag("f1-season-progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress == null) {
            Text("—", color = Muted, fontSize = 11.sp)
        } else {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text("${progress.counted}/${progress.total}", color = RaceRed, fontSize = 11.sp)
            }
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp)
                .testTag("f1-season-progress-bar"), color = RaceRed, trackColor = Line)
        }
    }
}
