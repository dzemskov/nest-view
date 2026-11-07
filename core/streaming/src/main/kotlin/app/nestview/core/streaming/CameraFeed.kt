package app.nestview.core.streaming

import android.graphics.Bitmap
import app.nestview.core.model.AudioLevel
import app.nestview.core.model.FeedHealth
import app.nestview.core.model.VideoSize
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicBoolean

/** A live camera: video for any number of views, a level meter, health, and playback gain. */
interface CameraFeed {
    val cameraId: String
    val health: StateFlow<FeedHealth>
    val audioLevel: StateFlow<AudioLevel>
    val videoSize: StateFlow<VideoSize?>

    /** A small recent snapshot, shown greyed out while the picture is not live. */
    val lastFrame: StateFlow<Bitmap?>

    fun addVideoSink(sink: VideoSink)
    fun removeVideoSink(sink: VideoSink)

    /** Playback gain (0 = muted). Muted feeds keep their level meter. */
    fun setAudioGain(gain: Float)

    /** Drops the current stream and connects again right away. */
    fun reconnect()
}

internal interface ManagedFeed : CameraFeed {
    fun start()
    fun stop()
}

/** A claim on a feed; the stream stops shortly after the last lease is closed. */
class FeedLease internal constructor(
    val feed: CameraFeed,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
