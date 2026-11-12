package app.nestview.core.analysis

import app.nestview.core.model.ActiveAlert
import app.nestview.core.model.AlertSettings
import app.nestview.core.model.AlertType
import app.nestview.core.model.AudioLevel
import app.nestview.core.model.FeedStatus
import app.nestview.core.model.SoundAlertPrefs

/** What the engine needs to know about one camera, sampled a few times a second. */
data class CameraSignal(
    val cameraId: String,
    val name: String,
    val level: AudioLevel,
    val status: FeedStatus,
    val soundAlert: SoundAlertPrefs,
)

/** The monitoring device itself. */
data class DeviceSignal(
    val online: Boolean = true,
    val batteryPercent: Int? = null,
    val charging: Boolean = true,
)

/** The alerts on screen now, and the ones that started with this update (to chime for). */
data class AlertUpdate(val active: List<ActiveAlert>, val fired: List<ActiveAlert>)

/**
 * Turns camera and device signals into alerts. Pure and clock-driven, so it's tested with virtual
 * time.
 *
 * - **Sound:** a camera's sound stays above its line (dB above the room's usual noise) for its
 *   sustain time. Short breaks of up to [GAP_MS] don't reset it (a crying child stops to breathe).
 *   The alert stays until dismissed; after a dismissal it can fire again after [SOUND_COOLDOWN_MS].
 * - **Camera lost:** no live picture for the configured time. **Offline:** no network for
 *   [OFFLINE_AFTER_MS]; it replaces the per-camera alarms, which are its consequence.
 *   **Low battery:** at or below [LOW_BATTERY_PERCENT] % and not charging.
 * - Alarms clear themselves when the problem goes away. Dismissing one silences it until the
 *   problem has cleared and come back.
 */
class AlertEngine {
    private class SoundState {
        var loudSince: Long? = null
        var lastLoudAt: Long = 0
        var quietUntil: Long = Long.MIN_VALUE
        var snoozedUntil: Long = Long.MIN_VALUE
    }

    /** A condition that must hold for a while before it counts, and can be acknowledged. */
    private class Condition {
        var since: Long? = null
        var acknowledged = false

        fun update(bad: Boolean, now: Long) {
            if (bad) {
                if (since == null) since = now
            } else {
                since = null
                acknowledged = false
            }
        }

        fun heldFor(now: Long): Long = since?.let { now - it } ?: -1
    }

    private val sound = HashMap<String, SoundState>()
    private val lost = HashMap<String, Condition>()
    private val offline = Condition()
    private val battery = Condition()
    private val active = LinkedHashMap<String, ActiveAlert>()

    fun update(now: Long, cameras: List<CameraSignal>, device: DeviceSignal, settings: AlertSettings): AlertUpdate {
        val fired = mutableListOf<ActiveAlert>()
        val ids = cameras.map { it.cameraId }.toSet()
        // Cameras that left the view take their alerts with them.
        sound.keys.retainAll(ids)
        lost.keys.retainAll(ids)
        active.values.removeAll { it.cameraId != null && it.cameraId !in ids }

        // Network first: without it, every camera is "lost" and one alarm says why.
        offline.update(!device.online, now)
        val isOffline = offline.heldFor(now) >= OFFLINE_AFTER_MS
        alarm(KEY_OFFLINE, AlertType.OFFLINE, null, null, offline, settings.offline && isOffline, now, fired)

        val low = device.batteryPercent != null && !device.charging &&
            device.batteryPercent <= (if (battery.since != null) LOW_BATTERY_CLEAR_PERCENT else LOW_BATTERY_PERCENT)
        battery.update(low, now)
        alarm(KEY_BATTERY, AlertType.LOW_BATTERY, null, null, battery, settings.lowBattery && low, now, fired)

        for (camera in cameras) {
            updateSound(camera, now, fired)
            val condition = lost.getOrPut(camera.cameraId) { Condition() }
            condition.update(!camera.status.showsLivePicture && camera.status != FeedStatus.STOPPED, now)
            val isLost = condition.heldFor(now) >= settings.cameraLostAfterSeconds * 1000L
            alarm(lostKey(camera.cameraId), AlertType.CAMERA_LOST, camera.cameraId, camera.name, condition,
                settings.cameraLost && isLost && !isOffline, now, fired)
        }
        return AlertUpdate(active.values.sortedByDescending { it.firedAtMs }, fired)
    }

    private fun updateSound(camera: CameraSignal, now: Long, fired: MutableList<ActiveAlert>) {
        val prefs = camera.soundAlert
        val key = soundKey(camera.cameraId)
        if (!prefs.enabled) {
            sound.remove(camera.cameraId)
            active.remove(key)
            return
        }
        val state = sound.getOrPut(camera.cameraId) { SoundState() }
        val level = camera.level
        val loud = camera.status.showsLivePicture && level.levelDb >= AudioLevel.QUIET_BELOW_DB &&
            level.aboveFloorDb >= prefs.thresholdDb
        if (loud) {
            if (state.loudSince == null) state.loudSince = now
            state.lastLoudAt = now
        } else if (state.loudSince != null && now - state.lastLoudAt > GAP_MS) {
            state.loudSince = null
        }
        val since = state.loudSince
        val sustained = since != null && loud && now - since >= prefs.sustainSeconds * 1000L

        val existing = active[key]
        when {
            existing != null -> if (loud) active[key] = existing.copy(lastSeenAtMs = now)
            sustained && now >= state.quietUntil && now >= state.snoozedUntil -> {
                val alert = ActiveAlert(key, AlertType.SOUND, camera.cameraId, camera.name, since!!, now, now)
                active[key] = alert
                fired += alert
            }
        }
    }

    private fun alarm(
        key: String,
        type: AlertType,
        cameraId: String?,
        name: String?,
        condition: Condition,
        raised: Boolean,
        now: Long,
        fired: MutableList<ActiveAlert>,
    ) {
        if (!raised || condition.acknowledged) {
            active.remove(key)
            return
        }
        if (key !in active) {
            val alert = ActiveAlert(key, type, cameraId, name, condition.since ?: now, now, now)
            active[key] = alert
            fired += alert
        } else {
            active[key] = active.getValue(key).copy(lastSeenAtMs = now, cameraName = name)
        }
    }

    /** "I've seen it": sound alerts may fire again after a cooldown; alarms wait until the problem clears. */
    fun dismiss(key: String, now: Long) {
        val alert = active.remove(key) ?: return
        when (alert.type) {
            AlertType.SOUND -> alert.cameraId?.let { sound[it]?.quietUntil = now + SOUND_COOLDOWN_MS }
            AlertType.CAMERA_LOST -> alert.cameraId?.let { lost[it]?.acknowledged = true }
            AlertType.OFFLINE -> offline.acknowledged = true
            AlertType.LOW_BATTERY -> battery.acknowledged = true
        }
    }

    /** No sound alerts from this camera until [untilMs]. */
    fun snooze(cameraId: String, untilMs: Long) {
        active.remove(soundKey(cameraId))
        sound.getOrPut(cameraId) { SoundState() }.snoozedUntil = untilMs
    }

    /** Forgets everything (monitoring stopped). */
    fun reset() {
        sound.clear()
        lost.clear()
        active.clear()
        offline.update(false, 0)
        battery.update(false, 0)
    }

    companion object {
        const val GAP_MS = 1_500L
        const val SOUND_COOLDOWN_MS = 60_000L
        const val OFFLINE_AFTER_MS = 10_000L
        const val LOW_BATTERY_PERCENT = 15
        const val LOW_BATTERY_CLEAR_PERCENT = 20
        const val KEY_OFFLINE = "offline"
        const val KEY_BATTERY = "battery"

        fun soundKey(cameraId: String) = "sound:$cameraId"
        fun lostKey(cameraId: String) = "lost:$cameraId"
    }
}
