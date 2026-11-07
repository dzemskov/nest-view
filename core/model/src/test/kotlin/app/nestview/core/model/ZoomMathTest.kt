package app.nestview.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    private val video = 16f / 9f

    @Test
    fun fitAtScaleOneShowsWholePicture() {
        val r = ZoomMath.visibleRegion(ZoomWindow.None, video, video)
        assertTrue(r.isFull)
    }

    @Test
    fun fillInSquareTileCropsSides() {
        val r = ZoomMath.visibleRegion(ZoomWindow.None, video, 1f)
        assertEquals(1f, r.height, 1e-4f)
        assertEquals(9f / 16f, r.width, 1e-4f)
        assertEquals((1f - 9f / 16f) / 2f, r.left, 1e-4f)
    }

    @Test
    fun fillInWideTileCropsTopAndBottom() {
        val r = ZoomMath.visibleRegion(ZoomWindow.None, video, 3f)
        assertEquals(1f, r.width, 1e-4f)
        assertEquals(video / 3f, r.height, 1e-4f)
    }

    @Test
    fun zoomRegionIsClampedInsidePicture() {
        val r = ZoomMath.visibleRegion(ZoomWindow(scale = 4f, centerX = 0f, centerY = 1f), video, video)
        assertEquals(0f, r.left, 1e-4f)
        assertEquals(1f, r.bottom, 1e-4f)
        assertEquals(0.25f, r.width, 1e-4f)
    }

    @Test
    fun pinchKeepsPointUnderFingers() {
        val start = ZoomWindow.None
        val z = ZoomMath.applyGesture(start, video, video, centroidX = 0.25f, centroidY = 0.25f, zoomFactor = 2f, panX = 0f, panY = 0f)
        val r = ZoomMath.visibleRegion(z, video, video)
        // Picture point (0.25, 0.25) must still be at 25 % of the viewport.
        assertEquals(0.25f, r.left + 0.25f * r.width, 1e-4f)
        assertEquals(0.25f, r.top + 0.25f * r.height, 1e-4f)
        assertEquals(2f, z.scale, 1e-4f)
    }

    @Test
    fun dragRightMovesViewToTheLeft() {
        val zoomed = ZoomWindow(scale = 2f)
        val z = ZoomMath.applyGesture(zoomed, video, video, 0.5f, 0.5f, 1f, panX = 0.2f, panY = 0f)
        assertTrue(z.centerX < 0.5f)
    }

    @Test
    fun zoomCannotGoBelowOne() {
        val z = ZoomMath.applyGesture(ZoomWindow.None, video, video, 0.5f, 0.5f, 0.3f, 0f, 0f)
        assertEquals(1f, z.scale, 1e-4f)
    }
}
