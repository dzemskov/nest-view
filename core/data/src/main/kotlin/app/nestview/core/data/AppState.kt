package app.nestview.core.data

import app.nestview.core.model.CameraInfo
import app.nestview.core.model.CameraPrefs
import app.nestview.core.model.MonitorView
import kotlinx.serialization.Serializable

/** App-wide choices that survive switching between demo and real cameras. */
@Serializable
data class AppSettings(
    /** Keep streams and sound running with the screen off or another app in front. */
    val backgroundListening: Boolean = true,
    /** Android only lets an app ask for notifications a couple of times, so ask once, on purpose. */
    val askedForNotifications: Boolean = false,
)

/** Everything the app remembers, stored as one JSON document. */
@Serializable
data class AppState(
    val demoMode: Boolean = false,
    /** Last known camera list (so the monitor opens instantly, without waiting for Google). */
    val cameras: List<CameraInfo> = emptyList(),
    val cameraPrefs: Map<String, CameraPrefs> = emptyMap(),
    /** Cameras the user removed from the monitor; not re-added when the list refreshes. */
    val hiddenCameraIds: Set<String> = emptySet(),
    val views: List<MonitorView> = emptyList(),
    val selectedViewId: String? = null,
    val settings: AppSettings = AppSettings(),
) {
    val selectedView: MonitorView?
        get() = views.firstOrNull { it.id == selectedViewId } ?: views.firstOrNull()

    fun prefsFor(cameraId: String): CameraPrefs = cameraPrefs[cameraId] ?: CameraPrefs()

    fun displayName(cameraId: String): String =
        cameraPrefs[cameraId]?.nickname?.takeIf { it.isNotBlank() }
            ?: cameras.firstOrNull { it.id == cameraId }?.name
            ?: "Camera"
}

/** Pure state transitions, kept separate so they're easy to test. */
object AppStateOps {

    /**
     * Applies a fresh camera list: new cameras join the "All cameras" view (unless hidden), vanished
     * cameras leave every view, and existing order is kept.
     */
    fun withCameras(state: AppState, cameras: List<CameraInfo>): AppState {
        val ids = cameras.map { it.id }
        val idSet = ids.toSet()
        val visible = ids.filter { it !in state.hiddenCameraIds }

        val views = state.views.map { view ->
            val kept = view.cameraIds.filter { it in idSet }
            val cameraIds = if (view.id == MonitorView.ALL_CAMERAS_ID) kept + visible.filter { it !in kept } else kept
            view.copy(cameraIds = cameraIds, audio = view.audio.retainOnly(cameraIds))
        }.ifEmpty {
            listOf(MonitorView(id = MonitorView.ALL_CAMERAS_ID, name = "All cameras", cameraIds = visible))
        }
        val selected = state.selectedViewId?.takeIf { id -> views.any { it.id == id } } ?: views.first().id
        return state.copy(
            cameras = cameras,
            views = views,
            selectedViewId = selected,
            cameraPrefs = state.cameraPrefs.filterKeys { it in idSet },
            hiddenCameraIds = state.hiddenCameraIds.filterTo(mutableSetOf()) { it in idSet },
        )
    }

    fun setCameraShown(state: AppState, cameraId: String, shown: Boolean): AppState {
        val hidden = if (shown) state.hiddenCameraIds - cameraId else state.hiddenCameraIds + cameraId
        val views = state.views.map { view ->
            val ids = when {
                !shown -> view.cameraIds - cameraId
                view.id == MonitorView.ALL_CAMERAS_ID && cameraId !in view.cameraIds -> view.cameraIds + cameraId
                else -> view.cameraIds
            }
            view.copy(cameraIds = ids, audio = view.audio.retainOnly(ids))
        }
        return state.copy(hiddenCameraIds = hidden, views = views)
    }
}
