package app.nestview.feature.monitor.layout

import app.nestview.core.model.LayoutSpec
import app.nestview.core.model.VideoSize
import kotlin.math.min

/** A tile's place in the grid, in pixels. */
data class TileRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Places camera tiles in a container. Auto mode tries every grid shape (and a "one large plus
 * small" layout) and keeps the one that shows the most picture, i.e. the largest total area of
 * videos fitted into their tiles.
 */
object LayoutEngine {

    fun compute(
        spec: LayoutSpec,
        ids: List<String>,
        aspects: Map<String, Float>,
        width: Float,
        height: Float,
        gap: Float = 0f,
        focusedId: String? = null,
    ): Map<String, TileRect> {
        if (ids.isEmpty() || width <= 0f || height <= 0f) return emptyMap()
        if (focusedId != null && focusedId in ids) return mapOf(focusedId to TileRect(0f, 0f, width, height))
        val landscape = width >= height
        return when (spec) {
            LayoutSpec.Auto -> auto(ids, aspects, width, height, gap, landscape)
            is LayoutSpec.Grid -> grid(ids, spec.columns.coerceIn(1, ids.size), width, height, gap)
            is LayoutSpec.Focus -> focus(ids, spec.primaryId?.takeIf { it in ids } ?: ids.first(), aspects, width, height, gap, landscape)
            LayoutSpec.Row -> grid(ids, ids.size, width, height, gap)
            LayoutSpec.Column -> grid(ids, 1, width, height, gap)
        }
    }

    internal fun auto(
        ids: List<String>,
        aspects: Map<String, Float>,
        width: Float,
        height: Float,
        gap: Float,
        landscape: Boolean,
    ): Map<String, TileRect> {
        var best: Map<String, TileRect> = emptyMap()
        var bestScore = -1f
        for (cols in 1..ids.size) {
            val rows = (ids.size + cols - 1) / cols
            val layout = grid(ids, cols, width, height, gap)
            // Tiny bias so ties go to side-by-side in landscape and stacked in portrait.
            val bias = 1f + 0.001f * (if (landscape) cols else rows)
            val score = score(layout, aspects) * bias
            if (score > bestScore) {
                best = layout
                bestScore = score
            }
        }
        if (ids.size >= 3) {
            val layout = focus(ids, ids.first(), aspects, width, height, gap, landscape)
            // Equal tiles are preferred for watching several children; focus must be clearly better.
            val score = score(layout, aspects) * 0.9f
            if (score > bestScore) best = layout
        }
        return best
    }

    internal fun grid(ids: List<String>, columns: Int, width: Float, height: Float, gap: Float): Map<String, TileRect> {
        val n = ids.size
        val cols = columns.coerceIn(1, n)
        val rows = (n + cols - 1) / cols
        val cellW = (width - gap * (cols - 1)) / cols
        val cellH = (height - gap * (rows - 1)) / rows
        val result = LinkedHashMap<String, TileRect>()
        ids.forEachIndexed { i, id ->
            val row = i / cols
            val col = i % cols
            val inRow = if (row == rows - 1) n - cols * (rows - 1) else cols
            val rowWidth = inRow * cellW + (inRow - 1) * gap
            val x0 = (width - rowWidth) / 2f // centre an incomplete last row
            result[id] = TileRect(x0 + col * (cellW + gap), row * (cellH + gap), cellW, cellH)
        }
        return result
    }

    internal fun focus(
        ids: List<String>,
        primary: String,
        aspects: Map<String, Float>,
        width: Float,
        height: Float,
        gap: Float,
        landscape: Boolean,
    ): Map<String, TileRect> {
        val others = ids.filter { it != primary }
        if (others.isEmpty()) return mapOf(primary to TileRect(0f, 0f, width, height))
        val k = others.size
        val aspect = others.map { aspects[it] ?: VideoSize.DEFAULT_ASPECT }.average().toFloat()
        val result = LinkedHashMap<String, TileRect>()
        if (landscape) {
            val smallH = (height - gap * (k - 1)) / k
            val stripW = min(smallH * aspect, width * 0.4f)
            result[primary] = TileRect(0f, 0f, width - stripW - gap, height)
            others.forEachIndexed { i, id -> result[id] = TileRect(width - stripW, i * (smallH + gap), stripW, smallH) }
        } else {
            val smallW = (width - gap * (k - 1)) / k
            val stripH = min(smallW / aspect, height * 0.4f)
            result[primary] = TileRect(0f, 0f, width, height - stripH - gap)
            others.forEachIndexed { i, id -> result[id] = TileRect(i * (smallW + gap), height - stripH, smallW, stripH) }
        }
        return result
    }

    /** Total area of the videos when each is fitted (letterboxed) into its tile. */
    internal fun score(layout: Map<String, TileRect>, aspects: Map<String, Float>): Float =
        layout.entries.sumOf { (id, r) -> fittedArea(r, aspects[id] ?: VideoSize.DEFAULT_ASPECT).toDouble() }.toFloat()

    private fun fittedArea(r: TileRect, aspect: Float): Float {
        val w = min(r.width, r.height * aspect)
        return w * (w / aspect)
    }
}
