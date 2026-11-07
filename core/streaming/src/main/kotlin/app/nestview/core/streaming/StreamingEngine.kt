package app.nestview.core.streaming

import app.nestview.core.model.CameraStreamApi
import app.nestview.core.model.DemoCameras
import app.nestview.core.streaming.demo.DemoFeed
import app.nestview.core.streaming.webrtc.WebRtcEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every camera stream for the whole process, outside any screen, so rotation, settings and
 * picture-in-picture never drop a stream. Feeds are shared and reference-counted; a feed stops
 * [GRACE_MS] after its last lease closes, which absorbs quick app switches.
 */
@Singleton
class StreamingEngine @Inject constructor(
    private val environment: WebRtcEnvironment,
    private val api: CameraStreamApi,
    private val network: NetworkMonitor,
    private val frameCache: LastFrameCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val holders = HashMap<String, Holder>()

    private class Holder(val feed: ManagedFeed) {
        var refs = 0
        var stopJob: Job? = null
    }

    /** Shared GL context for renderers. */
    val eglContext: EglBase.Context get() = environment.eglBase.eglBaseContext

    fun acquire(cameraId: String): FeedLease = synchronized(lock) {
        val holder = holders.getOrPut(cameraId) { Holder(createFeed(cameraId)).also { it.feed.start() } }
        holder.stopJob?.cancel()
        holder.stopJob = null
        holder.refs++
        FeedLease(holder.feed) { release(cameraId, holder) }
    }

    private fun release(cameraId: String, holder: Holder) = synchronized(lock) {
        holder.refs--
        if (holder.refs > 0) return@synchronized
        holder.stopJob = scope.launch {
            delay(GRACE_MS)
            synchronized(lock) {
                if (holder.refs <= 0 && holders[cameraId] === holder) {
                    holders.remove(cameraId)
                    holder.feed.stop()
                }
            }
        }
    }

    private fun createFeed(cameraId: String): ManagedFeed =
        if (cameraId.startsWith(DemoCameras.ID_PREFIX)) {
            DemoFeed(cameraId, scope)
        } else {
            NestWebRtcFeed(cameraId, environment, api, network.isOnline, frameCache, scope)
        }

    companion object {
        const val GRACE_MS = 60_000L
    }
}
