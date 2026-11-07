package app.nestview.core.sdm.api

import app.nestview.core.model.CameraInfo
import app.nestview.core.model.VideoSize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal val SdmJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
internal data class DevicesResponse(val devices: List<DeviceDto> = emptyList())

@Serializable
internal data class DeviceDto(
    val name: String,
    val type: String = "",
    val traits: JsonObject = JsonObject(emptyMap()),
    val parentRelations: List<ParentRelation> = emptyList(),
)

@Serializable
internal data class ParentRelation(val parent: String = "", val displayName: String = "")

@Serializable
internal data class ExecuteCommandRequest(val command: String, val params: JsonObject)

@Serializable
internal data class ExecuteCommandResponse(val results: JsonObject? = null)

@Serializable
internal data class GoogleErrorEnvelope(val error: GoogleError? = null)

@Serializable
internal data class GoogleError(val code: Int = 0, val message: String = "", val status: String = "")

internal object Traits {
    const val INFO = "sdm.devices.traits.Info"
    const val LIVE_STREAM = "sdm.devices.traits.CameraLiveStream"
    const val WEB_RTC = "WEB_RTC"
}

internal sealed interface DeviceMapping {
    data class Camera(val info: CameraInfo) : DeviceMapping
    data object UnsupportedCamera : DeviceMapping
    data object NotACamera : DeviceMapping
}

/** Maps an SDM device to a camera the app can stream (WebRTC only). */
internal fun DeviceDto.toMapping(): DeviceMapping {
    val live = traits[Traits.LIVE_STREAM] as? JsonObject ?: return DeviceMapping.NotACamera
    val protocols = (live["supportedProtocols"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    if (Traits.WEB_RTC !in protocols) return DeviceMapping.UnsupportedCamera

    val resolution = (live["maxVideoResolution"] as? JsonObject)?.let { r ->
        val w = (r["width"] as? JsonPrimitive)?.intOrNull ?: 0
        val h = (r["height"] as? JsonPrimitive)?.intOrNull ?: 0
        if (w > 0 && h > 0) VideoSize(w, h) else null
    }
    val customName = ((traits[Traits.INFO] as? JsonObject)?.get("customName") as? JsonPrimitive)
        ?.contentOrNull?.takeIf { it.isNotBlank() }
    val room = parentRelations.firstOrNull()?.displayName?.takeIf { it.isNotBlank() }
    val displayName = customName ?: room ?: typeLabel(type)
    return DeviceMapping.Camera(CameraInfo(id = name, name = displayName, room = room, maxResolution = resolution))
}

private fun typeLabel(type: String): String = when {
    type.endsWith(".DOORBELL") -> "Doorbell"
    type.endsWith(".DISPLAY") -> "Display"
    else -> "Camera"
}
