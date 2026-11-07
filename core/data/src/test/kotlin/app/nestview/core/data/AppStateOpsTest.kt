package app.nestview.core.data

import app.nestview.core.model.AudioMix
import app.nestview.core.model.CameraInfo
import app.nestview.core.model.MonitorView
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateOpsTest {
    private fun cam(id: String) = CameraInfo(id = id, name = id)

    @Test
    fun firstRefreshCreatesAllCamerasView() {
        val s = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b")))
        assertEquals(listOf("a", "b"), s.selectedView!!.cameraIds)
        assertEquals(MonitorView.ALL_CAMERAS_ID, s.selectedViewId)
    }

    @Test
    fun refreshKeepsOrderAddsNewAndDropsGone() {
        val start = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b"), cam("c")))
        val reordered = start.copy(
            views = start.views.map {
                it.copy(cameraIds = listOf("c", "a", "b"), audio = AudioMix(muted = setOf("b"), soloId = "c"))
            },
        )
        val s = AppStateOps.withCameras(reordered, listOf(cam("a"), cam("c"), cam("d")))
        val view = s.selectedView!!
        assertEquals(listOf("c", "a", "d"), view.cameraIds)
        assertEquals(emptySet<String>(), view.audio.muted)
        assertEquals("c", view.audio.soloId)
    }

    @Test
    fun hiddenCamerasStayHidden() {
        val start = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b")))
        val hidden = AppStateOps.setCameraShown(start, "b", shown = false)
        assertEquals(listOf("a"), hidden.selectedView!!.cameraIds)
        val refreshed = AppStateOps.withCameras(hidden, listOf(cam("a"), cam("b")))
        assertEquals(listOf("a"), refreshed.selectedView!!.cameraIds)
        val shown = AppStateOps.setCameraShown(refreshed, "b", shown = true)
        assertEquals(listOf("a", "b"), shown.selectedView!!.cameraIds)
    }
}
