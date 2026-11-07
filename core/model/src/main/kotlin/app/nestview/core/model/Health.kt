package app.nestview.core.model

enum class FeedStatus {
    /** First connection attempt, no picture yet. */
    CONNECTING,

    /** Frames are arriving on time. */
    LIVE,

    /** The newest frame is a few seconds old. */
    DELAYED,

    /** No new frames for a while, or the picture hasn't changed at all: never mistake this for live. */
    FROZEN,

    /** The stream dropped and a new one is being set up. */
    RECONNECTING,

    /** The camera is offline or not available for streaming. */
    OFFLINE,

    /** Google sign-in expired or was revoked. */
    AUTH_REQUIRED,

    /** Not streaming (nobody is watching). */
    STOPPED,
    ;

    val showsLivePicture: Boolean get() = this == LIVE || this == DELAYED
}

data class StreamStats(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val jitterMs: Double = 0.0,
    val packetsLost: Long = 0,
    val framesDecoded: Long = 0,
)

/** All times are on the app's monotonic clock (`SystemClock.elapsedRealtime`). */
data class FeedHealth(
    val status: FeedStatus = FeedStatus.CONNECTING,
    val lastFrameAtMs: Long? = null,
    val liveSinceMs: Long? = null,
    val reconnects: Int = 0,
    val message: String? = null,
    val stats: StreamStats? = null,
    val nextRetryAtMs: Long? = null,
)
