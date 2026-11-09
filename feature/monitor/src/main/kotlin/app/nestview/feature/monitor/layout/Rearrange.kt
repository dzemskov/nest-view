package app.nestview.feature.monitor.layout

import app.nestview.core.model.LayoutSpec

/** The order logic behind dragging tiles around: pure, so it's easy to test. */
object Rearrange {

    /**
     * The order tiles appear in on screen. In "one large + small" the large camera comes first,
     * so slot 0 is always the large tile and dropping a camera there makes it the large one.
     */
    fun displayOrder(spec: LayoutSpec, ids: List<String>): List<String> {
        val primary = (spec as? LayoutSpec.Focus)?.primaryId?.takeIf { it in ids } ?: return ids
        return listOf(primary) + ids.filter { it != primary }
    }

    /** The layout to draw for [order]: in "one large + small", the first camera is the large one. */
    fun specFor(spec: LayoutSpec, order: List<String>): LayoutSpec =
        if (spec is LayoutSpec.Focus && order.isNotEmpty()) LayoutSpec.Focus(order.first()) else spec

    /** Moves [id] into [targetId]'s place; the cameras in between shift by one. */
    fun moveTo(order: List<String>, id: String, targetId: String): List<String> {
        if (id == targetId || id !in order || targetId !in order) return order
        val target = order.indexOf(targetId)
        return (order - id).toMutableList().apply { add(target, id) }
    }

    /** Moves [id] by [delta] places (for accessibility actions), clamped to the ends. */
    fun moveBy(order: List<String>, id: String, delta: Int): List<String> {
        val from = order.indexOf(id)
        if (from < 0) return order
        val to = (from + delta).coerceIn(0, order.lastIndex)
        return (order - id).toMutableList().apply { add(to, id) }
    }

    /**
     * The tile under ([x], [y]), ignoring a margin of [inset] (a fraction of the tile) around each
     * tile's edge, so the order doesn't flicker while the finger crosses a border.
     */
    fun tileAt(rects: Map<String, TileRect>, x: Float, y: Float, inset: Float = 0.1f): String? =
        rects.entries.firstOrNull { (_, r) ->
            val dx = r.width * inset
            val dy = r.height * inset
            x >= r.x + dx && x <= r.x + r.width - dx && y >= r.y + dy && y <= r.y + r.height - dy
        }?.key
}
