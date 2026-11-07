package app.nestview.core.model

import kotlinx.serialization.Serializable

/**
 * Digital zoom of one tile: [scale] >= 1 and the centre of the visible region, in normalized
 * picture coordinates (0..1, origin top-left).
 */
@Serializable
data class ZoomWindow(
    val scale: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    val isZoomed: Boolean get() = scale > 1.01f

    companion object {
        val None = ZoomWindow()
        const val MAX_SCALE = 8f
    }
}

/** A rectangle in normalized picture coordinates. */
data class NormRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val isFull: Boolean
        get() = left <= EPS && top <= EPS && width >= 1f - EPS && height >= 1f - EPS

    companion object {
        val Full = NormRect(0f, 0f, 1f, 1f)
        private const val EPS = 0.001f
    }
}

/**
 * Maps zoom state to the part of the picture that is visible.
 *
 * The *viewport* is the area the picture is drawn into. In [ScaleMode.FIT] the viewport has the
 * picture's own aspect ratio, so at scale 1 the whole picture is visible. In [ScaleMode.FILL] the
 * viewport is the tile, so at scale 1 the picture is centre-cropped to the tile's shape.
 */
object ZoomMath {

    /** Size of the visible region at scale 1, as fractions of the picture. */
    fun baseSize(videoAspect: Float, viewportAspect: Float): Pair<Float, Float> =
        if (viewportAspect >= videoAspect) {
            1f to (videoAspect / viewportAspect).coerceIn(0f, 1f)
        } else {
            (viewportAspect / videoAspect).coerceIn(0f, 1f) to 1f
        }

    fun visibleRegion(zoom: ZoomWindow, videoAspect: Float, viewportAspect: Float): NormRect {
        val (bw, bh) = baseSize(videoAspect, viewportAspect)
        val s = zoom.scale.coerceIn(1f, ZoomWindow.MAX_SCALE)
        val w = bw / s
        val h = bh / s
        val cx = clampCenter(zoom.centerX, w)
        val cy = clampCenter(zoom.centerY, h)
        return NormRect(cx - w / 2f, cy - h / 2f, w, h)
    }

    fun clamp(zoom: ZoomWindow, videoAspect: Float, viewportAspect: Float): ZoomWindow {
        val r = visibleRegion(zoom, videoAspect, viewportAspect)
        return ZoomWindow(
            scale = zoom.scale.coerceIn(1f, ZoomWindow.MAX_SCALE),
            centerX = r.left + r.width / 2f,
            centerY = r.top + r.height / 2f,
        )
    }

    /**
     * Applies one step of a pinch/pan gesture. [centroidX]/[centroidY] and [panX]/[panY] are
     * fractions of the viewport size; the picture point under the centroid stays under it.
     */
    fun applyGesture(
        zoom: ZoomWindow,
        videoAspect: Float,
        viewportAspect: Float,
        centroidX: Float,
        centroidY: Float,
        zoomFactor: Float,
        panX: Float,
        panY: Float,
    ): ZoomWindow {
        val region = visibleRegion(zoom, videoAspect, viewportAspect)
        val fx = region.left + centroidX * region.width
        val fy = region.top + centroidY * region.height
        val newScale = (zoom.scale * zoomFactor).coerceIn(1f, ZoomWindow.MAX_SCALE)
        val (bw, bh) = baseSize(videoAspect, viewportAspect)
        val w = bw / newScale
        val h = bh / newScale
        val cx = fx - centroidX * w + w / 2f - panX * w
        val cy = fy - centroidY * h + h / 2f - panY * h
        return clamp(ZoomWindow(newScale, cx, cy), videoAspect, viewportAspect)
    }

    private fun clampCenter(center: Float, size: Float): Float =
        if (size >= 1f) 0.5f else center.coerceIn(size / 2f, 1f - size / 2f)
}
