package app.nestview.core.streaming.webrtc

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun PeerConnection.createOfferAwait(): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) =
                cont.resumeWithException(IllegalStateException("createOffer failed: $error"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        },
        MediaConstraints(),
    )
}

internal suspend fun PeerConnection.setLocalDescriptionAwait(sdp: SessionDescription) =
    awaitSet("setLocalDescription") { observer -> setLocalDescription(observer, sdp) }

internal suspend fun PeerConnection.setRemoteDescriptionAwait(sdp: SessionDescription) =
    awaitSet("setRemoteDescription") { observer -> setRemoteDescription(observer, sdp) }

internal suspend fun PeerConnection.getStatsAwait(): RTCStatsReport = suspendCancellableCoroutine { cont ->
    getStats(RTCStatsCollectorCallback { report -> cont.resume(report) })
}

private suspend fun awaitSet(what: String, call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
    call(
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) =
                cont.resumeWithException(IllegalStateException("$what failed: $error"))
        },
    )
}
