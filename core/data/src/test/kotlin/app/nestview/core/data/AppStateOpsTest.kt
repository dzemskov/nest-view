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

    @Test
    fun removeFromAllCamerasStaysRemovedAfterRefresh() {
        val start = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b"), cam("c")))
        val removed = AppStateOps.removeFromView(start, MonitorView.ALL_CAMERAS_ID, "b")
        assertEquals(listOf("a", "c"), removed.selectedView!!.cameraIds)
        val refreshed = AppStateOps.withCameras(removed, listOf(cam("a"), cam("b"), cam("c")))
        assertEquals(listOf("a", "c"), refreshed.selectedView!!.cameraIds)
    }

    @Test
    fun addPutsCameraAtEndAndRefreshKeepsIt() {
        val start = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b")))
        val removed = AppStateOps.removeFromView(start, MonitorView.ALL_CAMERAS_ID, "a")
        val added = AppStateOps.addToView(removed, MonitorView.ALL_CAMERAS_ID, "a")
        assertEquals(listOf("b", "a"), added.selectedView!!.cameraIds)
        assertEquals(emptySet<String>(), added.hiddenCameraIds)
        val refreshed = AppStateOps.withCameras(added, listOf(cam("a"), cam("b")))
        assertEquals(listOf("b", "a"), refreshed.selectedView!!.cameraIds)
    }

    @Test
    fun undoRestoresPositionAndAudio() {
        val start = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b"), cam("c")))
        val withAudio = start.copy(views = start.views.map { it.copy(audio = AudioMix(muted = setOf("b"), soloId = "b")) })
        val before = withAudio.selectedView!!
        val removed = AppStateOps.removeFromView(withAudio, before.id, "b")
        assertEquals(AudioMix(), removed.selectedView!!.audio)
        val restored = AppStateOps.addToView(removed, before.id, "b", index = 1, audio = before.audio)
        assertEquals(listOf("a", "b", "c"), restored.selectedView!!.cameraIds)
        assertEquals(before.audio, restored.selectedView!!.audio)
    }

    @Test
    fun addingTwiceDoesNothing() {
        val start = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b")))
        assertEquals(start, AppStateOps.addToView(start, MonitorView.ALL_CAMERAS_ID, "a"))
    }

    @Test
    fun reorderKeepsUnmentionedCamerasAndIgnoresStrangers() {
        val start = AppStateOps.withCameras(AppState(), listOf(cam("a"), cam("b"), cam("c"), cam("d")))
        val s = AppStateOps.reorderView(start, MonitorView.ALL_CAMERAS_ID, listOf("c", "x", "a"))
        assertEquals(listOf("c", "a", "b", "d"), s.selectedView!!.cameraIds)
    }
}
