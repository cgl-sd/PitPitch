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
import kotlin.math.sqrt

internal data class CarImageBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun carImageBounds(record: JSONObject, field: String = "contentBounds"): CarImageBounds? {
    val bounds = record.optJSONArray(field) ?: return null
    val width = record.optDouble("width").toFloat()
    val height = record.optDouble("height").toFloat()
    if (bounds.length() != 4 || width <= 0 || height <= 0) return null
    val result = CarImageBounds(bounds.optDouble(0).toFloat() / width, bounds.optDouble(1).toFloat() / height,
        bounds.optDouble(2).toFloat() / width, bounds.optDouble(3).toFloat() / height)
    return result.takeIf { it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 && it.right > it.left && it.bottom > it.top }
}

/** Consistent optical area within the logo slot, ignoring transparent source margins. */
internal data class LogoContentScale(val bounds: CarImageBounds) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val width = srcSize.width * (bounds.right - bounds.left)
        val height = srcSize.height * (bounds.bottom - bounds.top)
        if (width <= 0 || height <= 0) return ScaleFactor(1f, 1f)
        val scale = min(sqrt(dstSize.width * dstSize.height * .20f / (width * height)),
            min(dstSize.width / width, dstSize.height / height))
        return ScaleFactor(scale, scale)
    }
}

/** The detected face occupies the same fraction of every portrait, across all seasons. */
internal data class PortraitContentScale(val face: CarImageBounds) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val height = srcSize.height * (face.bottom - face.top)
        val scale = if (height > 0) dstSize.height * .46f / height else 1f
        return ScaleFactor(scale, scale)
    }
}
internal data class PortraitContentAlignment(val face: CarImageBounds) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset = IntOffset(
        (space.width * .5f - size.width * (face.left + face.right) * .5f).roundToInt(),
        (space.height * .45f - size.height * (face.top + face.bottom) * .5f).roundToInt())
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
