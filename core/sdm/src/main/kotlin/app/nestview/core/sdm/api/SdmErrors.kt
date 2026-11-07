package app.nestview.core.sdm.api

import app.nestview.core.model.StreamApiException

internal object SdmErrors {

    /** Maps a Google API error response to the app's stream errors. */
    fun from(httpCode: Int, body: String): StreamApiException {
        val error = runCatching { SdmJson.decodeFromString<GoogleErrorEnvelope>(body).error }.getOrNull()
        val status = error?.status.orEmpty()
        val message = error?.message?.takeIf { it.isNotBlank() } ?: "HTTP $httpCode"
        return when {
            httpCode == 429 || status == "RESOURCE_EXHAUSTED" ->
                StreamApiException.RateLimited("Google API rate limit reached: $message")
            httpCode == 401 || status == "UNAUTHENTICATED" ->
                StreamApiException.Unauthorized("Google sign-in is no longer valid: $message")
            httpCode == 403 || status == "PERMISSION_DENIED" ->
                StreamApiException.Unauthorized("No permission for this camera: $message")
            status == "FAILED_PRECONDITION" || status == "NOT_FOUND" ->
                StreamApiException.CameraUnavailable("Camera unavailable: $message")
            else -> StreamApiException.Transient("Google API error ($httpCode $status): $message")
        }
    }
}
