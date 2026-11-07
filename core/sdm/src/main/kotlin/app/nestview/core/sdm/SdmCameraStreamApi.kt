package app.nestview.core.sdm

import app.nestview.core.model.CameraStreamApi
import app.nestview.core.model.StreamApiException
import app.nestview.core.model.WebRtcStreamInfo
import app.nestview.core.sdm.api.SdmClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SdmCameraStreamApi @Inject constructor(
    private val client: SdmClient,
    private val limiter: CommandRateLimiter,
) : CameraStreamApi {

    override suspend fun generateWebRtcStream(cameraId: String, offerSdp: String): WebRtcStreamInfo {
        limiter.acquire(cameraId, CommandPriority.GENERATE)
        val r = client.executeCommand(cameraId, GENERATE, buildJsonObject { put("offerSdp", offerSdp) })
        return WebRtcStreamInfo(
            answerSdp = r.string("answerSdp"),
            mediaSessionId = r.string("mediaSessionId"),
            expiresInMs = expiresIn(r.optString("expiresAt")),
        )
    }

    override suspend fun extendWebRtcStream(cameraId: String, mediaSessionId: String): Long {
        limiter.acquire(cameraId, CommandPriority.EXTEND)
        val r = client.executeCommand(cameraId, EXTEND, buildJsonObject { put("mediaSessionId", mediaSessionId) })
        return expiresIn(r.optString("expiresAt"))
    }

    override suspend fun stopWebRtcStream(cameraId: String, mediaSessionId: String) {
        if (!limiter.acquire(cameraId, CommandPriority.STOP)) return
        client.executeCommand(cameraId, STOP, buildJsonObject { put("mediaSessionId", mediaSessionId) })
    }

    private fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.string(key: String): String =
        optString(key) ?: throw StreamApiException.Transient("Google API response is missing '$key'")

    companion object {
        private const val PREFIX = "sdm.devices.commands.CameraLiveStream."
        const val GENERATE = PREFIX + "GenerateWebRtcStream"
        const val EXTEND = PREFIX + "ExtendWebRtcStream"
        const val STOP = PREFIX + "StopWebRtcStream"

        private const val DEFAULT_LIFETIME_MS = 5 * 60_000L

        /**
         * Remaining lifetime from the wall-clock expiry. Falls back to the documented 5 minutes
         * when the phone's clock is clearly off.
         */
        fun expiresIn(expiresAt: String?, nowEpochMs: Long = System.currentTimeMillis()): Long {
            val at = expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: return DEFAULT_LIFETIME_MS
            val remaining = at - nowEpochMs
            return if (remaining in 10_000..15 * 60_000L) remaining else DEFAULT_LIFETIME_MS
        }
    }
}
