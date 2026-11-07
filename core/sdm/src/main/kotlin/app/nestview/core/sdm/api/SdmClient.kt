package app.nestview.core.sdm.api

import app.nestview.core.model.StreamApiException
import app.nestview.core.sdm.SdmConfigRepository
import app.nestview.core.sdm.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal client for the Smart Device Management REST API. */
@Singleton
class SdmClient @Inject internal constructor(
    private val http: OkHttpClient,
    private val auth: AuthManager,
    private val configRepository: SdmConfigRepository,
) {
    internal suspend fun listDevices(): List<DeviceDto> {
        val projectId = configRepository.config.value.projectId
        if (projectId.isBlank()) throw StreamApiException.NotConfigured("Device Access project ID is not set")
        val body = call("GET", "v1/enterprises/$projectId/devices", null)
        return SdmJson.decodeFromString<DevicesResponse>(body).devices
    }

    internal suspend fun executeCommand(deviceName: String, command: String, params: JsonObject): JsonObject {
        val payload = SdmJson.encodeToString(ExecuteCommandRequest.serializer(), ExecuteCommandRequest(command, params))
        val body = call("POST", "v1/$deviceName:executeCommand", payload)
        return SdmJson.decodeFromString<ExecuteCommandResponse>(body).results ?: JsonObject(emptyMap())
    }

    private suspend fun call(method: String, path: String, jsonBody: String?): String {
        val first = send(method, path, jsonBody, auth.freshAccessToken())
        if (first.code != 401) return first.unwrap()
        // The token may have been revoked or expired early: refresh once and retry.
        auth.invalidateAccessToken()
        return send(method, path, jsonBody, auth.freshAccessToken()).unwrap()
    }

    private suspend fun send(method: String, path: String, jsonBody: String?, token: String): HttpResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(BASE_URL + path)
                .header("Authorization", "Bearer $token")
                .method(method, jsonBody?.toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    HttpResult(response.code, response.body.string())
                }
            } catch (e: IOException) {
                throw StreamApiException.Transient("Network error: ${e.message}", e)
            }
        }

    private class HttpResult(val code: Int, val body: String) {
        fun unwrap(): String = if (code in 200..299) body else throw SdmErrors.from(code, body)
    }

    private companion object {
        const val BASE_URL = "https://smartdevicemanagement.googleapis.com/"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
