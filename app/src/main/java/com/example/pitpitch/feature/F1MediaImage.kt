package com.example.pitpitch.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clipToBounds
import coil.compose.AsyncImage
import com.example.pitpitch.data.F1Media
import com.example.pitpitch.data.F1Race
import com.example.pitpitch.ui.Muted
import com.example.pitpitch.ui.PanelSurface

@Composable fun F1MediaImage(role: String, entity: String?, season: Int?, label: String, modifier: Modifier, crop: Boolean = false, race: F1Race? = null) {
    val context = LocalContext.current
    val references by com.example.pitpitch.data.repository.localBackend(context).references.collectAsStateWithLifecycle()
    val model = remember(role, entity, season, context, race, references) {
        if (role == "layout") race?.let { F1Media.layout(context, it) }
        else F1Media.asset(context, role, entity, season)
    }
    val imageBounds = remember(role, entity, season, references) {
        if (role in setOf("car", "logo", "driver")) references.f1Media.optJSONObject("$role/$season/$entity")?.let {
            if (role == "driver" && it.optString("faceGeometrySha256") != it.optString("sha256")) null
            else carImageBounds(it, if (role == "driver") "faceBounds" else "contentBounds")
        } else null
    }
    var loaded by remember(model) { mutableStateOf(false) }
    Box(modifier.clipToBounds().testTag("f1-media-$role-${season ?: "venue"}-$entity"), contentAlignment = Alignment.Center) {
        if (!loaded) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (role == "driver") Icons.Outlined.Person else Icons.Outlined.Image,
                "$label 图片暂缺", tint = Muted)
        }
        // Let Coil size the portrait to the measured slot; the normalized face crop keeps the framing.
        AsyncImage(coil.request.ImageRequest.Builder(context).data(model).build(), if (loaded) label else null, Modifier.matchParentSize(),
            contentScale = if (imageBounds != null) when (role) {
                "driver" -> PortraitContentScale(imageBounds)
                "logo" -> LogoContentScale(imageBounds)
                else -> CarContentScale(imageBounds)
            }
                else if (crop || role == "driver") ContentScale.Crop else ContentScale.Fit,
            alignment = if (imageBounds != null) {
                if (role == "driver") PortraitContentAlignment(imageBounds) else CarContentAlignment(imageBounds)
            }
                else if (role == "driver") Alignment.TopCenter else Alignment.Center,
            onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable fun CircuitLayout(race: F1Race, modifier: Modifier = Modifier.fillMaxWidth().height(220.dp)) {
    F1MediaImage("layout", race.id, race.season, "${race.circuit} 赛道布局图",
        modifier, race = race)
}

@Composable fun CircuitPhoto(race: F1Race, modifier: Modifier) {
    F1MediaImage("circuit", race.circuitId, null, "${race.circuit} 场地照片",
        modifier.background(PanelSurface), crop = true)
}
