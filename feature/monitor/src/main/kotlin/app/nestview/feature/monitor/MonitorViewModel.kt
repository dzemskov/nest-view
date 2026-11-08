package app.nestview.feature.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nestview.core.data.AppState
import app.nestview.core.data.MonitorRepository
import app.nestview.core.model.AudioMix
import app.nestview.core.model.LayoutSpec
import app.nestview.core.model.ScaleMode
import app.nestview.core.model.VideoSize
import app.nestview.core.model.ZoomWindow
import app.nestview.core.streaming.CameraFeed
import app.nestview.core.streaming.StreamingEngine
import app.nestview.feature.monitor.session.MonitorSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import javax.inject.Inject

data class TileState(
    val cameraId: String,
    val title: String,
    val feed: CameraFeed,
    val volume: Float,
    val muted: Boolean,
    val solo: Boolean,
    val scaleMode: ScaleMode,
    val zoom: ZoomWindow,
    val pinnedZoom: ZoomWindow?,
    val aspect: Float,
)

data class MonitorUiState(
    val loading: Boolean = true,
    val viewId: String? = null,
    val viewName: String = "",
    val tiles: List<TileState> = emptyList(),
    val portraitLayout: LayoutSpec = LayoutSpec.Auto,
    val landscapeLayout: LayoutSpec = LayoutSpec.Auto,
    val focusedId: String? = null,
    val controlsVisible: Boolean = true,
    val allMuted: Boolean = false,
    val isDemo: Boolean = false,
    val backgroundListening: Boolean = false,
    val askedForNotifications: Boolean = true,
)

/**
 * Keeps the [MonitorSession] streaming while the monitor exists (activity-scoped, so streams survive
 * rotation and visits to Settings) and turns stored preferences into tile state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val repository: MonitorRepository,
    private val session: MonitorSession,
    engine: StreamingEngine,
) : ViewModel() {

    val eglContext: EglBase.Context = engine.eglContext

    private val screenHold = session.holdForScreen()
    private val feeds = session.feeds
    private val focusedId = MutableStateFlow<String?>(null)
    private val zooms = MutableStateFlow<Map<String, ZoomWindow>>(emptyMap())
    private val controlsVisible = MutableStateFlow(true)

    private val aspects: Flow<Map<String, Float>> = feeds.flatMapLatest { map ->
        if (map.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(map.values.map { feed -> feed.videoSize.map { feed.cameraId to (it?.aspect ?: VideoSize.DEFAULT_ASPECT) } }) {
                it.toMap()
            }
        }
    }

    private data class Ui(val focused: String?, val zooms: Map<String, ZoomWindow>, val controls: Boolean)

    val uiState: StateFlow<MonitorUiState> = combine(
        combine(repository.state, feeds, aspects) { s, f, a -> Triple(s, f, a) },
        combine(focusedId, zooms, controlsVisible) { f, z, c -> Ui(f, z, c) },
    ) { (state, feedMap, aspectMap), ui -> build(state, feedMap, aspectMap, ui) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MonitorUiState())

    private fun build(state: AppState, feedMap: Map<String, CameraFeed>, aspectMap: Map<String, Float>, ui: Ui): MonitorUiState {
        val view = state.selectedView ?: return MonitorUiState(
            loading = false,
            isDemo = state.demoMode,
            backgroundListening = state.settings.backgroundListening,
            askedForNotifications = state.settings.askedForNotifications,
        )
        val tiles = view.cameraIds.mapNotNull { id ->
            val feed = feedMap[id] ?: return@mapNotNull null
            val prefs = state.prefsFor(id)
            TileState(
                cameraId = id,
                title = state.displayName(id),
                feed = feed,
                volume = prefs.volume,
                muted = view.audio.isMuted(id),
                solo = view.audio.soloId == id,
                scaleMode = prefs.scaleMode,
                zoom = ui.zooms[id] ?: prefs.pinnedZoom ?: ZoomWindow.None,
                pinnedZoom = prefs.pinnedZoom,
                aspect = aspectMap[id] ?: VideoSize.DEFAULT_ASPECT,
            )
        }
        return MonitorUiState(
            loading = false,
            viewId = view.id,
            viewName = view.name,
            tiles = tiles,
            portraitLayout = view.portrait,
            landscapeLayout = view.landscape,
            focusedId = ui.focused?.takeIf { it in view.cameraIds },
            controlsVisible = ui.controls,
            allMuted = view.audio.allMuted,
            isDemo = state.demoMode,
            backgroundListening = state.settings.backgroundListening,
            askedForNotifications = state.settings.askedForNotifications,
        )
    }

    fun setControlsVisible(visible: Boolean) {
        controlsVisible.value = visible
    }

    fun toggleControls() = controlsVisible.update { !it }

    fun focus(cameraId: String) = focusedId.update { if (it == cameraId) null else cameraId }

    fun unfocus() {
        focusedId.value = null
    }

    fun toggleMute(cameraId: String) = editAudio { mix, ids -> mix.toggleMute(cameraId, ids) }

    fun toggleSolo(cameraId: String) = editAudio { mix, _ -> mix.toggleSolo(cameraId) }

    fun toggleAllMuted() = editAudio { mix, _ -> mix.toggleAllMuted() }

    /** Applies a volume live while the slider moves, without writing to storage. */
    fun previewVolume(cameraId: String, volume: Float) = session.previewVolume(cameraId, volume)

    fun commitVolume(cameraId: String, volume: Float) {
        viewModelScope.launch {
            repository.updateCameraPrefs(cameraId) { it.copy(volume = volume.coerceIn(0f, AudioMix.MAX_VOLUME)) }
            session.previewVolume(cameraId, null)
        }
    }

    /** Remembers that the app asked for notifications, so it doesn't ask on every start. */
    fun markAskedForNotifications() {
        viewModelScope.launch { repository.updateSettings { it.copy(askedForNotifications = true) } }
    }

    fun setZoom(cameraId: String, zoom: ZoomWindow) = zooms.update { it + (cameraId to zoom) }

    /** Saves the current zoom as this camera's default crop. */
    fun pinZoom(cameraId: String) {
        val zoom = uiState.value.tiles.firstOrNull { it.cameraId == cameraId }?.zoom ?: return
        viewModelScope.launch {
            repository.updateCameraPrefs(cameraId) { it.copy(pinnedZoom = zoom.takeIf { z -> z.isZoomed }) }
            zooms.update { it - cameraId }
        }
    }

    fun resetZoom(cameraId: String) = zooms.update { it + (cameraId to ZoomWindow.None) }

    fun forgetPinnedZoom(cameraId: String) {
        viewModelScope.launch {
            repository.updateCameraPrefs(cameraId) { it.copy(pinnedZoom = null) }
            zooms.update { it - cameraId }
        }
    }

    fun setScaleMode(cameraId: String, mode: ScaleMode) {
        viewModelScope.launch {
            repository.updateCameraPrefs(cameraId) { it.copy(scaleMode = mode, pinnedZoom = null) }
            zooms.update { it - cameraId }
        }
    }

    fun setLayout(landscape: Boolean, spec: LayoutSpec) = editView { view ->
        if (landscape) view.copy(landscape = spec) else view.copy(portrait = spec)
    }

    /** Uses the one-large-plus-small layout with this camera as the large tile. */
    fun makePrimary(cameraId: String, landscape: Boolean) = setLayout(landscape, LayoutSpec.Focus(cameraId))

    fun reconnect(cameraId: String) {
        feeds.value[cameraId]?.reconnect()
    }

    private fun editAudio(transform: (AudioMix, List<String>) -> AudioMix) = editView { view ->
        view.copy(audio = transform(view.audio, view.cameraIds))
    }

    private fun editView(transform: (app.nestview.core.model.MonitorView) -> app.nestview.core.model.MonitorView) {
        viewModelScope.launch {
            val viewId = repository.current().selectedView?.id ?: return@launch
            repository.updateView(viewId, transform)
        }
    }

    override fun onCleared() {
        screenHold.close()
    }
}
