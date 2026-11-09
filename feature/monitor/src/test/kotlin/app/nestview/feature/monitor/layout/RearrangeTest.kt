package app.nestview.feature.monitor.layout

import app.nestview.core.model.LayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RearrangeTest {
    private val abcd = listOf("a", "b", "c", "d")

    @Test
    fun movingForwardTakesTheTargetsPlace() {
        assertEquals(listOf("b", "c", "a", "d"), Rearrange.moveTo(abcd, "a", "c"))
    }

    @Test
    fun movingBackwardTakesTheTargetsPlace() {
        assertEquals(listOf("a", "d", "b", "c"), Rearrange.moveTo(abcd, "d", "b"))
    }

    @Test
    fun movingOntoItselfOrUnknownChangesNothing() {
        assertEquals(abcd, Rearrange.moveTo(abcd, "b", "b"))
        assertEquals(abcd, Rearrange.moveTo(abcd, "x", "b"))
    }

    @Test
    fun moveByIsClampedToTheEnds() {
        assertEquals(listOf("b", "a", "c", "d"), Rearrange.moveBy(abcd, "a", 1))
        assertEquals(listOf("d", "a", "b", "c"), Rearrange.moveBy(abcd, "d", -9))
        assertEquals(abcd, Rearrange.moveBy(abcd, "d", 1))
    }

    @Test
    fun focusLayoutPutsLargeCameraFirst() {
        assertEquals(listOf("c", "a", "b", "d"), Rearrange.displayOrder(LayoutSpec.Focus("c"), abcd))
        assertEquals(abcd, Rearrange.displayOrder(LayoutSpec.Focus(null), abcd))
        assertEquals(abcd, Rearrange.displayOrder(LayoutSpec.Grid(2), abcd))
        assertEquals(LayoutSpec.Focus("b"), Rearrange.specFor(LayoutSpec.Focus("c"), listOf("b", "c")))
        assertEquals(LayoutSpec.Auto, Rearrange.specFor(LayoutSpec.Auto, listOf("b", "c")))
    }

    @Test
    fun droppingOnTheLargeTileOfFocusLayoutMakesItLarge() {
        val order = Rearrange.displayOrder(LayoutSpec.Focus("c"), abcd)
        val moved = Rearrange.moveTo(order, "d", order.first())
        assertEquals("d", moved.first())
        assertEquals(LayoutSpec.Focus("d"), Rearrange.specFor(LayoutSpec.Focus("c"), moved))
    }

    @Test
    fun tileAtIgnoresTheBorderMargin() {
        val rects = LayoutEngine.grid(listOf("a", "b"), 2, 200f, 100f, 0f)
        assertEquals("a", Rearrange.tileAt(rects, 50f, 50f))
        assertEquals("b", Rearrange.tileAt(rects, 150f, 50f))
        assertNull(Rearrange.tileAt(rects, 101f, 50f)) // on the border between them
    }
}
