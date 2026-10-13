package com.example.pitpitch.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pitpitch.data.CircuitMap
import com.example.pitpitch.ui.FootballGreen
import com.example.pitpitch.ui.Muted

@Composable fun CircuitMapCard(map: CircuitMap?) {
    if (map == null) StatePanel("暂无赛道地图", "该赛道缺少可核实的轮廓，不使用其它赛道替代。")
    else Panel {
        Text(map.name)
        Canvas(Modifier.fillMaxWidth().height(180.dp).testTag("circuit-map").semantics { contentDescription = "${map.name} 参考轮廓，北朝上" }) {
            if (map.points.size > 1) {
                val pad = 12.dp.toPx(); val scale = minOf(size.width - pad*2, size.height - pad*2)
                val width = map.points.maxOf { it.x }.toFloat()*scale; val height = map.points.maxOf { it.y }.toFloat()*scale
                val x = (size.width - width)/2; val y = (size.height - height)/2
                val path = Path()
                map.points.forEachIndexed { i,p -> if (i == 0) path.moveTo(x+p.x.toFloat()*scale,y+p.y.toFloat()*scale) else path.lineTo(x+p.x.toFloat()*scale,y+p.y.toFloat()*scale) }
                drawPath(path, FootballGreen, style = Stroke(3.dp.toPx()))
            }
        }
    }
}
