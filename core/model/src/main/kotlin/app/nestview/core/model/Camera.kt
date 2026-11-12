package app.nestview.core.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoSize(val width: Int, val height: Int) {
    val aspect: Float
        get() = if (width <= 0 || height <= 0) DEFAULT_ASPECT else width.toFloat() / height

    companion object {
        const val DEFAULT_ASPECT = 16f / 9f
    }
}

/**
 * A camera the app can show.
 *
 * [id] is the SDM device resource name (`enterprises/{project}/devices/{device}`), or a
 * `demo/...` id for the built-in demo cameras.
 */
@Serializable
data class CameraInfo(
    val id: String,
    val name: String,
    val room: String? = null,
    val maxResolution: VideoSize? = null,
) {
    val isDemo: Boolean get() = id.startsWith(DemoCameras.ID_PREFIX)
}

/** Per-camera preferences that apply in every view. */
@Serializable
data class CameraPrefs(
    val nickname: String? = null,
    /** Playback gain, 0..[AudioMix.MAX_VOLUME] (0..300 %). */
    val volume: Float = 1f,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    /** A saved crop (e.g. "crib view") restored every time the camera is shown. */
    val pinnedZoom: ZoomWindow? = null,
    val soundAlert: SoundAlertPrefs = SoundAlertPrefs(),
)

@Serializable
enum class ScaleMode {
    /** Show the whole picture, letterboxed. */
    FIT,

    /** Fill the tile, cropping the picture; pan chooses the visible part. */
    FILL,
}

object DemoCameras {
    const val ID_PREFIX = "demo/"

    val all: List<CameraInfo> = listOf(
        CameraInfo(id = "${ID_PREFIX}nursery", name = "Nursery", room = "Nursery", maxResolution = VideoSize(480, 270)),
        CameraInfo(id = "${ID_PREFIX}kids-room", name = "Kids room", room = "Kids room", maxResolution = VideoSize(480, 270)),
        CameraInfo(id = "${ID_PREFIX}playroom", name = "Playroom", room = "Playroom", maxResolution = VideoSize(480, 270)),
        CameraInfo(id = "${ID_PREFIX}kitchen", name = "Kitchen", room = "Kitchen", maxResolution = VideoSize(480, 270)),
    )

    fun variantOf(cameraId: String): Int = all.indexOfFirst { it.id == cameraId }.coerceAtLeast(0)
}
