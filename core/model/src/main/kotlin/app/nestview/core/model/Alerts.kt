package app.nestview.core.model

import kotlinx.serialization.Serializable

/** A camera's sound alert: fires when sound stays above the line for long enough. */
@Serializable
data class SoundAlertPrefs(
    val enabled: Boolean = false,
    /** How far above the room's usual noise (its learned background level) sound must rise, in dB. */
    val thresholdDb: Float = DEFAULT_THRESHOLD_DB,
    /** How long it must last. */
    val sustainSeconds: Int = DEFAULT_SUSTAIN_SECONDS,
) {
    companion object {
        const val DEFAULT_THRESHOLD_DB = 14f
        const val MIN_THRESHOLD_DB = 6f
        const val MAX_THRESHOLD_DB = 30f
        const val DEFAULT_SUSTAIN_SECONDS = 3
        val SUSTAIN_CHOICES = listOf(1, 3, 10)
    }
}

/** Safety alarms and what alerts do. Alarms sound even when every camera is muted. */
@Serializable
data class AlertSettings(
    val cameraLost: Boolean = true,
    val cameraLostAfterSeconds: Int = 30,
    val offline: Boolean = true,
    val lowBattery: Boolean = true,
    /** Play an alerting camera's sound even if it's muted, until the alert is dismissed. */
    val unmuteOnAlert: Boolean = true,
    val vibrate: Boolean = true,
)

enum class AlertType { SOUND, CAMERA_LOST, OFFLINE, LOW_BATTERY }

/**
 * An alert on screen (and in a notification when the app is in the background). Times are on the
 * monotonic clock (`SystemClock.elapsedRealtime`).
 */
data class ActiveAlert(
    val key: String,
    val type: AlertType,
    val cameraId: String? = null,
    val cameraName: String? = null,
    /** When the condition started: the sound began, the camera went away, the network dropped. */
    val startedAtMs: Long,
    /** The last moment the condition was seen (for sound: the last loud moment). */
    val lastSeenAtMs: Long,
    val firedAtMs: Long,
) {
    /** Safety alarms repeat until dismissed and sound even when everything is muted. */
    val isAlarm: Boolean get() = type != AlertType.SOUND

    val title: String
        get() = when (type) {
            AlertType.SOUND -> "Sound in ${cameraName ?: "a camera"}"
            AlertType.CAMERA_LOST -> "${cameraName ?: "A camera"} isn't streaming"
            AlertType.OFFLINE -> "No internet connection"
            AlertType.LOW_BATTERY -> "This device's battery is low"
        }
}
