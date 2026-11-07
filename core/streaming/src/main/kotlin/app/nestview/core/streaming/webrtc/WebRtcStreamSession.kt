package app.nestview.core.streaming.webrtc

import android.os.SystemClock
import android.util.Log
import app.nestview.core.model.CameraStreamApi
import app.nestview.core.model.StreamApiException
import app.nestview.core.model.StreamStats
import app.nestview.core.streaming.SessionConnection
import app.nestview.core.streaming.StreamSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One WebRTC session with a Nest camera, set up the way Google requires: Unified Plan, receive-only
 * audio (Opus) and video (H.264) plus a data channel, in that order.
 */
internal class WebRtcStreamSession private constructor(
    private val cameraId: String,
    private val api: CameraStreamApi,
    private val peerConnection: PeerConnection,
    private val dataChannel: DataChannel,
    private val videoTrack: VideoTrack,
    private val audioTrack: AudioTrack,
    private val observer: Observer,
    private val videoIngress: VideoSink,
    private val audioIngress: AudioTrackSink,
) : StreamSession {

    override var id: String = ""
        private set
    override var initialExpiresInMs: Long = 0
        private set
    override val connection: StateFlow<SessionConnection> get() = observer.state
    override val framesReceived: Long get() = frames.get()

    private val lock = Any()
    private val frames = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    @Volatile private var disposed = false
    @Volatile private var active = false
    @Volatile private var gain = 1f
    private var lastStatsBytes = -1L
    private var lastStatsAtMs = 0L

    private val videoGate = VideoSink { frame ->
        frames.incrementAndGet()
        if (active) videoIngress.onFrame(frame)
    }

    private val audioGate = AudioTrackSink { data, bits, rate, channels, count, timestamp ->
        if (active) audioIngress.onData(data, bits, rate, channels, count, timestamp)
    }

    private fun attach() {
        videoTrack.addSink(videoGate)
        audioTrack.addSink(audioGate)
        applyVolume()
    }

    override fun setActive(active: Boolean) {
        this.active = active
        applyVolume()
    }

    override fun setAudioGain(gain: Float) {
        this.gain = gain
        applyVolume()
    }

    /** Inactive sessions are silent. Muting uses volume 0, so level meters keep working. */
    private fun applyVolume() = synchronized(lock) {
        if (!disposed) audioTrack.setVolume(if (active) gain.toDouble() else 0.0)
    }

    override suspend fun extend(): Long = api.extendWebRtcStream(cameraId, id)

    override suspend fun stats(): StreamStats? {
        if (disposed) return null
        val report = peerConnection.getStatsAwait()
        val video = report.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
            ?: return null
        val m = video.members
        val bytes = (m["bytesReceived"] as? Number)?.toLong() ?: 0L
        val now = SystemClock.elapsedRealtime()
        val kbps = if (lastStatsBytes >= 0 && now > lastStatsAtMs) {
            (bytes - lastStatsBytes) * 8.0 / (now - lastStatsAtMs)
        } else {
            0.0
        }
        lastStatsBytes = bytes
        lastStatsAtMs = now
        return StreamStats(
            width = (m["frameWidth"] as? Number)?.toInt() ?: 0,
            height = (m["frameHeight"] as? Number)?.toInt() ?: 0,
            fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: 0.0,
            bitrateKbps = kbps,
            jitterMs = ((m["jitter"] as? Number)?.toDouble() ?: 0.0) * 1000.0,
            packetsLost = (m["packetsLost"] as? Number)?.toLong() ?: 0L,
            framesDecoded = (m["framesDecoded"] as? Number)?.toLong() ?: 0L,
        )
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        disposeLocal()
        val sessionId = id
        if (sessionId.isNotEmpty()) {
            try {
                api.stopWebRtcStream(cameraId, sessionId)
            } catch (e: Exception) {
                Log.d(TAG, "StopWebRtcStream failed (session expires anyway): ${e.message}")
            }
        }
    }

    private fun disposeLocal() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            active = false
            runCatching { videoTrack.removeSink(videoGate) }
            runCatching { audioTrack.removeSink(audioGate) }
            runCatching { dataChannel.dispose() }
        }
        observer.state.value = SessionConnection.CLOSED
        peerConnection.dispose()
    }

    private class Observer : PeerConnection.Observer {
        val state = MutableStateFlow(SessionConnection.CONNECTING)

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (state.value == SessionConnection.CLOSED) return
            state.value = when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> SessionConnection.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> SessionConnection.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> SessionConnection.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> SessionConnection.CLOSED
                else -> SessionConnection.CONNECTING
            }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    companion object {
        private const val TAG = "WebRtcStreamSession"
        private const val STUN = "stun:stun.l.google.com:19302"

        /**
         * Creates the peer connection, asks Google for a stream with our offer and applies the
         * answer. Video and audio flow to [videoIngress]/[audioIngress] once the session is active.
         */
        suspend fun open(
            environment: WebRtcEnvironment,
            api: CameraStreamApi,
            cameraId: String,
            videoIngress: VideoSink,
            audioIngress: AudioTrackSink,
        ): WebRtcStreamSession {
            val observer = Observer()
            val config = PeerConnection.RTCConfiguration(
                listOf(PeerConnection.IceServer.builder(STUN).createIceServer()),
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }
            val pc = environment.factory.createPeerConnection(config, observer)
                ?: throw StreamApiException.Transient("Could not create a WebRTC connection")

            var session: WebRtcStreamSession? = null
            try {
                // Order matters: audio, video, then the data channel.
                val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                val audioTx = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
                val videoTx = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
                val dataChannel = pc.createDataChannel("dataSendChannel", DataChannel.Init())
                val audioTrack = audioTx.receiver.track() as AudioTrack
                val videoTrack = videoTx.receiver.track() as VideoTrack

                session = WebRtcStreamSession(
                    cameraId, api, pc, dataChannel, videoTrack, audioTrack, observer, videoIngress, audioIngress,
                ).also { it.attach() }

                val offer = pc.createOfferAwait()
                pc.setLocalDescriptionAwait(offer)
                val sdp = SdpOffer.normalize(offer.description)
                SdpOffer.validate(sdp).forEach { Log.w(TAG, "SDP offer: $it") }

                val info = api.generateWebRtcStream(cameraId, sdp)
                pc.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, info.answerSdp))
                session.id = info.mediaSessionId
                session.initialExpiresInMs = info.expiresInMs
                return session
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    val s = session
                    if (s != null) s.close() else pc.dispose()
                }
                throw t
            }
        }
    }
}
