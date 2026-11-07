package app.nestview.core.model

/** Result of `GenerateWebRtcStream`. Durations are relative to when the response arrived. */
data class WebRtcStreamInfo(
    val answerSdp: String,
    val mediaSessionId: String,
    val expiresInMs: Long,
)

/** The camera streaming commands of Google's Smart Device Management API. */
interface CameraStreamApi {
    suspend fun generateWebRtcStream(cameraId: String, offerSdp: String): WebRtcStreamInfo

    /** Returns the new remaining lifetime. Battery cameras ignore extensions (lifetime doesn't grow). */
    suspend fun extendWebRtcStream(cameraId: String, mediaSessionId: String): Long

    /** Best effort; may be skipped when the rate limit is tight (the session expires anyway). */
    suspend fun stopWebRtcStream(cameraId: String, mediaSessionId: String)
}

sealed class StreamApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(message: String) : StreamApiException(message)
    class CameraUnavailable(message: String) : StreamApiException(message)
    class Unauthorized(message: String) : StreamApiException(message)
    class NotConfigured(message: String) : StreamApiException(message)
    class Transient(message: String, cause: Throwable? = null) : StreamApiException(message, cause)
}
