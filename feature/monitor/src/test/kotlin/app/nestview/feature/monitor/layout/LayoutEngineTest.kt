package app.nestview.feature.monitor.layout

import app.nestview.core.model.LayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {
    private fun ids(n: Int) = (1..n).map { "c$it" }

    private val phonePortrait = 1080f to 2340f
    private val phoneLandscape = 2340f to 1080f
    private val tabletLandscape = 2560f to 1600f
    private val tv = 1920f to 1080f

    private fun auto(n: Int, size: Pair<Float, Float>) =
        LayoutEngine.compute(LayoutSpec.Auto, ids(n), emptyMap(), size.first, size.second, gap = 6f)

    private fun columns(layout: Map<String, TileRect>) = layout.values.map { it.x }.distinct().size
    private fun rows(layout: Map<String, TileRect>) = layout.values.map { it.y }.distinct().size

    @Test
    fun singleCameraFillsScreen() {
        val r = auto(1, tv).getValue("c1")
        assertEquals(TileRect(0f, 0f, 1920f, 1080f), r)
    }

    @Test
    fun twoCamerasSideBySideInLandscapeAndStackedInPortrait() {
        val wide = auto(2, tv)
        assertEquals(1, rows(wide))
        val tall = auto(2, phonePortrait)
        assertEquals(1, columns(tall))
    }

    @Test
    fun fourCamerasOnTabletUseTwoByTwo() {
        val layout = auto(4, tabletLandscape)
        assertEquals(2, rows(layout))
        assertEquals(2, columns(layout))
    }

    @Test
    fun threeCamerasOnPortraitPhoneStack() {
        assertEquals(1, columns(auto(3, phonePortrait)))
    }

    @Test
    fun focusedCameraTakesWholeScreen() {
        val layout = LayoutEngine.compute(LayoutSpec.Auto, ids(4), emptyMap(), 1000f, 500f, focusedId = "c3")
        assertEquals(setOf("c3"), layout.keys)
    }

    @Test
    fun focusLayoutPutsStripOnLongSide() {
        val wide = LayoutEngine.compute(LayoutSpec.Focus("c2"), ids(3), emptyMap(), 1920f, 1080f)
        val primary = wide.getValue("c2")
        assertEquals(1080f, primary.height)
        assertTrue(wide.getValue("c1").x > primary.x)
        val tall = LayoutEngine.compute(LayoutSpec.Focus("c2"), ids(3), emptyMap(), 1080f, 1920f)
        assertEquals(1080f, tall.getValue("c2").width)
        assertTrue(tall.getValue("c1").y > 0f)
    }

    @Test
    fun everyLayoutStaysInsideAndDoesNotOverlap() {
        val sizes = listOf(phonePortrait, phoneLandscape, tabletLandscape, tv)
        val specs = listOf(LayoutSpec.Auto, LayoutSpec.Grid(2), LayoutSpec.Grid(3), LayoutSpec.Focus(), LayoutSpec.Row, LayoutSpec.Column)
        for ((w, h) in sizes) for (spec in specs) for (n in 1..6) {
            val layout = LayoutEngine.compute(spec, ids(n), emptyMap(), w, h, gap = 6f)
            assertEquals("$spec n=$n", n, layout.size)
            val rects = layout.values.toList()
            for (r in rects) {
                assertTrue("$spec n=$n inside", r.x >= -0.5f && r.y >= -0.5f && r.x + r.width <= w + 0.5f && r.y + r.height <= h + 0.5f)
                assertTrue(r.width > 0 && r.height > 0)
            }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val overlap = a.x < b.x + b.width - 0.5f && b.x < a.x + a.width - 0.5f &&
                    a.y < b.y + b.height - 0.5f && b.y < a.y + a.height - 0.5f
                assertTrue("$spec n=$n tiles $i and $j overlap", !overlap)
            }
        }
    }
}
