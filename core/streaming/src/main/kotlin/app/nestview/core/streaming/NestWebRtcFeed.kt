package app.nestview.core.streaming

import android.graphics.Bitmap
import android.os.SystemClock
import app.nestview.core.analysis.SoundLevelAnalyzer
import app.nestview.core.model.AudioLevel
import app.nestview.core.model.CameraStreamApi
import app.nestview.core.model.FeedHealth
import app.nestview.core.model.VideoSize
import app.nestview.core.streaming.frames.FrameTap
import app.nestview.core.streaming.frames.VideoFanout
import app.nestview.core.streaming.webrtc.WebRtcEnvironment
import app.nestview.core.streaming.webrtc.WebRtcStreamSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.webrtc.AudioTrackSink
import org.webrtc.VideoSink
import java.nio.ByteOrder

/** A Nest camera streamed over WebRTC through Google's SDM API. */
internal class NestWebRtcFeed(
    override val cameraId: String,
    private val environment: WebRtcEnvironment,
    private val api: CameraStreamApi,
    networkAvailable: StateFlow<Boolean>,
    private val frameCache: LastFrameCache,
    private val scope: CoroutineScope,
) : ManagedFeed {

    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val fanout = VideoFanout()
    private val _audioLevel = MutableStateFlow(AudioLevel.Silent)
    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    private val _lastFrame = MutableStateFlow<Bitmap?>(null)
    private val analyzer = SoundLevelAnalyzer { _audioLevel.value = it }

    private val controller = FeedController(
        cameraId = cameraId,
        factory = StreamSessionFactory { id ->
            WebRtcStreamSession.open(environment, api, id, videoIngress, audioIngress)
        },
        scope = scope,
        clock = clock,
        networkAvailable = networkAvailable,
    )

    private val videoIngress: VideoSink = FrameTap(
        clock = clock,
        downstream = fanout,
        listener = object : FrameTap.Listener {
            override fun onFrame(nowMs: Long) = controller.onFrame(nowMs)
            override fun onVideoSize(size: VideoSize) {
                _videoSize.value = size
            }
            override fun onThumbnail(luma: ByteArray, nowMs: Long) = controller.onThumbnail(luma, nowMs)
            override fun onSnapshot(bitmap: Bitmap) {
                _lastFrame.value = bitmap
            }
        },
    )

    private val audioIngress = AudioTrackSink { data, bits, rate, channels, count, _ ->
        if (bits != 16) return@AudioTrackSink
        val samples = data.order(ByteOrder.nativeOrder()).asShortBuffer()
        synchronized(analyzer) { analyzer.process(samples, count * channels, channels, rate) }
    }

    override val health: StateFlow<FeedHealth> get() = controller.health
    override val audioLevel: StateFlow<AudioLevel> = _audioLevel.asStateFlow()
    override val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()
    override val lastFrame: StateFlow<Bitmap?> = _lastFrame.asStateFlow()

    private var housekeeping: Job? = null

    override fun start() {
        controller.start()
        if (housekeeping?.isActive == true) return
        housekeeping = scope.launch {
            launch(Dispatchers.IO) {
                if (_lastFrame.value == null) frameCache.load(cameraId)?.let { cached ->
                    if (_lastFrame.value == null) _lastFrame.value = cached
                }
                while (true) {
                    delay(SNAPSHOT_SAVE_INTERVAL_MS)
                    _lastFrame.value?.let { frameCache.save(cameraId, it) }
                }
            }
            // No stale sound level while the picture isn't live.
            controller.health.map { it.status.showsLivePicture }.distinctUntilChanged().collect { live ->
                if (!live) {
                    synchronized(analyzer) { analyzer.reset() }
                    _audioLevel.value = AudioLevel.Silent
                }
            }
        }
    }

    override fun stop() {
        controller.stop()
        housekeeping?.cancel()
        housekeeping = null
        _audioLevel.value = AudioLevel.Silent
        _lastFrame.value?.let { snapshot -> scope.launch(Dispatchers.IO) { frameCache.save(cameraId, snapshot) } }
    }

    override fun addVideoSink(sink: VideoSink) = fanout.add(sink)
    override fun removeVideoSink(sink: VideoSink) = fanout.remove(sink)
    override fun setAudioGain(gain: Float) = controller.setAudioGain(gain)
    override fun reconnect() = controller.requestReconnect()

    private companion object {
        const val SNAPSHOT_SAVE_INTERVAL_MS = 60_000L
    }
}
