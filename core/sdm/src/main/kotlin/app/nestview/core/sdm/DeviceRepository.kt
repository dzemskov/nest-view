package app.nestview.core.sdm

import app.nestview.core.model.CameraInfo
import app.nestview.core.sdm.api.DeviceMapping
import app.nestview.core.sdm.api.SdmClient
import app.nestview.core.sdm.api.toMapping
import javax.inject.Inject
import javax.inject.Singleton

data class CameraDiscovery(
    val cameras: List<CameraInfo>,
    /** Cameras that only support RTSP (older models still in the Nest app). */
    val unsupported: Int,
)

@Singleton
class DeviceRepository @Inject constructor(private val client: SdmClient) {

    suspend fun discoverCameras(): CameraDiscovery {
        val mappings = client.listDevices().map { it.toMapping() }
        return CameraDiscovery(
            cameras = mappings.filterIsInstance<DeviceMapping.Camera>().map { it.info }.sortedBy { it.name.lowercase() },
            unsupported = mappings.count { it == DeviceMapping.UnsupportedCamera },
        )
    }
}
