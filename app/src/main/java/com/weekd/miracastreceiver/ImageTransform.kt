package com.weekd.miracastreceiver

internal data class ImageTransform(val scale: Float, val offsetX: Float, val offsetY: Float)

/** Drawable-to-viewport transform; offsets stay bounded so panning cannot lose the image. */
internal fun calculateImageTransform(
    imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float,
    rotationDegrees: Int, manager: ScaleManager?
): ImageTransform? {
    if (listOf(imageWidth, imageHeight, viewWidth, viewHeight).any { !it.isFinite() || it <= 0f }) return null
    val sideways = rotationDegrees % 180 != 0
    val rotatedWidth = if (sideways) imageHeight else imageWidth
    val rotatedHeight = if (sideways) imageWidth else imageHeight
    val fit = minOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
    val scale = when (manager?.mode) {
        ScaleMode.ASPECT_FILL -> maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        else -> fit * (manager?.mode?.factor ?: 1f)
    }
    val maxX = ((rotatedWidth * scale - viewWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((rotatedHeight * scale - viewHeight) / 2f).coerceAtLeast(0f)
    val offsetX = (manager?.offsetX ?: 0f).coerceIn(-maxX, maxX)
    val offsetY = (manager?.offsetY ?: 0f).coerceIn(-maxY, maxY)
    manager?.forceOffsetX(offsetX)
    manager?.forceOffsetY(offsetY)
    return ImageTransform(scale, offsetX, offsetY)
}
