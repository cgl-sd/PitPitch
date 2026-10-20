package com.example.pitpitch.feature

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

internal data class CarImageBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun carImageBounds(record: JSONObject): CarImageBounds? {
    val bounds = record.optJSONArray("contentBounds") ?: return null
    val width = record.optDouble("width").toFloat()
    val height = record.optDouble("height").toFloat()
    if (bounds.length() != 4 || width <= 0 || height <= 0) return null
    val result = CarImageBounds(bounds.optInt(0) / width, bounds.optInt(1) / height,
        bounds.optInt(2) / width, bounds.optInt(3) / height)
    return result.takeIf { it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 && it.right > it.left && it.bottom > it.top }
}

/** Match the visible width of the original 2026 Mercedes image (634 / 640), keeping aspect ratio. */
internal data class CarContentScale(val bounds: CarImageBounds) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        if (srcSize.width <= 0 || srcSize.height <= 0) return ScaleFactor(1f, 1f)
        val scale = min(dstSize.width * (634f / 640f) / (srcSize.width * (bounds.right - bounds.left)),
            dstSize.height / (srcSize.height * (bounds.bottom - bounds.top)))
        return ScaleFactor(scale, scale)
    }
}

/** Center the car body, rather than the transparent source canvas. No pixel data is rewritten. */
internal data class CarContentAlignment(val bounds: CarImageBounds) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset = IntOffset(
        (space.width / 2f - size.width * (bounds.left + bounds.right) / 2f).roundToInt(),
        (space.height / 2f - size.height * (bounds.top + bounds.bottom) / 2f).roundToInt(),
    )
}
