package app.nestview.feature.monitor.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.nestview.core.data.AppState
import app.nestview.core.data.MonitorRepository
import app.nestview.core.streaming.CameraFeed
import app.nestview.core.streaming.FeedLease
import app.nestview.core.streaming.StreamingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What the background notification shows. */
data class ListeningInfo(
    val enabled: Boolean,
    val cameraNames: List<String>,
    /** "Mute all" is on (the notification's button toggles it). */
    val allMuted: Boolean,
    /** No camera is audible, whatever the reason (mute all, every camera muted, solo of a quiet one…). */
    val silent: Boolean,
)

/**
 * Keeps the current view's cameras streaming and their sound playing, for the monitor screen while
 * the app is in front and for the background listening service otherwise. It's the one place that
 * turns the stored audio mix into playback gains, so the screen and the notification always agree.
 *
 * Streams run while a monitor screen exists and the app is in the foreground, or while the
 * background service holds the session. Otherwise sound stops at once and the streams close after
 * [StreamingEngine.GRACE_MS], so a quick look at another app doesn't reconnect anything.
 */
@Singleton
class MonitorSession @Inject constructor(
    private val repository: MonitorRepository,
    private val engine: StreamingEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val screens = MutableStateFlow(0)
    private val background = MutableStateFlow(0)
    private val appForeground = MutableStateFlow(false)
    private val volumePreview = MutableStateFlow<Pair<String, Float>?>(null)

    private val leases = mutableMapOf<String, FeedLease>()
    private val _feeds = MutableStateFlow<Map<String, CameraFeed>>(emptyMap())

    /** Feeds of the current view. Kept while inactive so the screen doesn't flash empty on return. */
    val feeds: StateFlow<Map<String, CameraFeed>> = _feeds.asStateFlow()

    /** True while the cameras should stream and play. */
    val active: StateFlow<Boolean> = combine(screens, appForeground, background) { s, fg, bg -> (s > 0 && fg) || bg > 0 }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val listeningInfo: Flow<ListeningInfo> = repository.state.map { it.listeningInfo() }.distinctUntilChanged()

    init {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    appForeground.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    appForeground.value = false
                }
            })
        }
        scope.launch {
            combine(repository.state.map { it.selectedView?.cameraIds.orEmpty() }.distinctUntilChanged(), active) { ids, on ->
                if (on) ids else emptyList()
            }.collect { syncLeases(it) }
        }
        scope.launch {
            combine(repository.state, _feeds, volumePreview, active) { state, feedMap, preview, on ->
                applyGains(state, feedMap, preview, on)
            }.collect {}
        }
    }

    /** Called by the monitor screen's view model; streams play while the app is in front. */
    fun holdForScreen(): AutoCloseable = hold(screens)

    /** Called by the background service; streams play whatever the app is doing. */
    fun holdInBackground(): AutoCloseable = hold(background)

    private fun hold(counter: MutableStateFlow<Int>): AutoCloseable {
        counter.update { it + 1 }
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) counter.update { it - 1 } }
    }

    /** Applies a volume live while a slider moves, without writing to storage. */
    fun previewVolume(cameraId: String, volume: Float?) {
        volumePreview.value = volume?.let { cameraId to it }
    }

    suspend fun toggleAllMuted() {
        val viewId = repository.current().selectedView?.id ?: return
        repository.updateView(viewId) { it.copy(audio = it.audio.toggleAllMuted()) }
    }

    private fun syncLeases(ids: List<String>) {
        (leases.keys - ids.toSet()).forEach { leases.remove(it)?.close() }
        if (ids.isEmpty()) return // keep the last feeds for the screen; their sound is off
        ids.forEach { id -> if (id !in leases) leases[id] = engine.acquire(id) }
        _feeds.value = ids.associateWith { leases.getValue(it).feed }
    }

    private fun applyGains(state: AppState, feedMap: Map<String, CameraFeed>, preview: Pair<String, Float>?, on: Boolean) {
        val view = state.selectedView
        for ((id, feed) in feedMap) {
            val gain = if (!on || view == null || id !in view.cameraIds) {
                0f
            } else {
                val volume = if (preview?.first == id) preview.second else state.prefsFor(id).volume
                view.audio.gainFor(id, volume)
            }
            feed.setAudioGain(gain)
        }
    }
}

private fun AppState.listeningInfo(): ListeningInfo {
    val view = selectedView
    val ids = view?.cameraIds.orEmpty()
    return ListeningInfo(
        enabled = settings.backgroundListening,
        cameraNames = ids.map { displayName(it) },
        allMuted = view?.audio?.allMuted == true,
        silent = view == null || ids.all { id -> view.audio.gainFor(id, prefsFor(id).volume) == 0f },
    )
}
