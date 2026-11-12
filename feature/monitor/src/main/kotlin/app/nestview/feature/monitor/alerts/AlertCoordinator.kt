package app.nestview.feature.monitor.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.nestview.core.analysis.AlertEngine
import app.nestview.core.analysis.CameraSignal
import app.nestview.core.analysis.DeviceSignal
import app.nestview.core.data.AppState
import app.nestview.core.data.MonitorRepository
import app.nestview.core.model.ActiveAlert
import app.nestview.core.model.AlertType
import app.nestview.core.streaming.NetworkMonitor
import app.nestview.feature.monitor.session.MonitorSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the cameras and this device while monitoring is on, and makes alerts heard: on screen
 * (banner, red tile, chime, vibration) when the app is in front, as notifications when it's hidden
 * with Keep listening on. An alerting camera's sound plays even if it's muted, until dismissed.
 */
@Singleton
class AlertCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: MonitorSession,
    private val repository: MonitorRepository,
    private val network: NetworkMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = AlertEngine()
    private val notifier = AlertNotifier(context)
    private val battery = context.getSystemService(BatteryManager::class.java)

    private val _alerts = MutableStateFlow<List<ActiveAlert>>(emptyList())

    /** Alerts showing now, newest first. */
    val alerts: StateFlow<List<ActiveAlert>> = _alerts.asStateFlow()

    private val _focusRequest = MutableStateFlow<String?>(null)

    /** A camera to show full screen because its notification was tapped. */
    val focusRequest: StateFlow<String?> = _focusRequest.asStateFlow()

    private var state = AppState()
    private val lastAnnounced = HashMap<String, Long>()
    private var device = DeviceSignal()
    private var batteryCheckedAt = Long.MIN_VALUE
    private var ringtone: Ringtone? = null

    init {
        notifier.createChannels()
        scope.launch { repository.state.collect { state = it } }
        scope.launch {
            session.active.collectLatest { on ->
                if (!on) {
                    clear()
                    return@collectLatest
                }
                while (true) {
                    tick()
                    delay(TICK_MS)
                }
            }
        }
        // Back in the app, the banner takes over from the notifications.
        scope.launch { session.appForeground.collect { if (it) notifier.cancelAll() } }
    }

    fun dismiss(key: String) {
        engine.dismiss(key, SystemClock.elapsedRealtime())
        publish(_alerts.value.filter { it.key != key })
    }

    fun snooze(cameraId: String) {
        engine.snooze(cameraId, SystemClock.elapsedRealtime() + SNOOZE_MS)
        publish(_alerts.value.filter { !(it.type == AlertType.SOUND && it.cameraId == cameraId) })
    }

    fun requestFocus(cameraId: String) {
        _focusRequest.value = cameraId
    }

    fun consumeFocusRequest() {
        _focusRequest.value = null
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val feeds = session.feeds.value
        val cameras = state.selectedView?.cameraIds.orEmpty().mapNotNull { id ->
            val feed = feeds[id] ?: return@mapNotNull null
            CameraSignal(id, state.displayName(id), feed.audioLevel.value, feed.health.value.status, state.prefsFor(id).soundAlert)
        }
        val update = engine.update(now, cameras, deviceSignal(now), state.settings.alerts)
        publish(update.active)
        update.fired.forEach { announce(it, now) }
        // Safety alarms repeat until someone dismisses them or the problem goes away.
        update.active.filter { it.isAlarm && it !in update.fired }.forEach { alert ->
            if (now - (lastAnnounced[alert.key] ?: now) >= ALARM_REPEAT_MS) announce(alert, now)
        }
        lastAnnounced.keys.retainAll(update.active.map { it.key }.toSet())
    }

    private fun publish(active: List<ActiveAlert>) {
        _alerts.value = active
        val unmute = state.settings.alerts.unmuteOnAlert
        session.setAlertAudible(if (unmute) active.filter { it.type == AlertType.SOUND }.mapNotNull { it.cameraId }.toSet() else emptySet())
        notifier.retain(active.map { it.key }.toSet())
    }

    private fun announce(alert: ActiveAlert, now: Long) {
        lastAnnounced[alert.key] = now
        if (session.appForeground.value) {
            chime(alert.isAlarm)
            if (state.settings.alerts.vibrate) vibrate(alert.isAlarm)
        } else {
            notifier.show(alert, playsSound = alert.type == AlertType.SOUND && state.settings.alerts.unmuteOnAlert)
        }
    }

    private fun deviceSignal(now: Long): DeviceSignal {
        if (now - batteryCheckedAt >= BATTERY_CHECK_MS) {
            batteryCheckedAt = now
            val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            device = DeviceSignal(online = true, batteryPercent = percent, charging = battery.isCharging)
        }
        return device.copy(online = network.isOnline.value)
    }

    private fun chime(alarm: Boolean) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        ringtone?.stop()
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            // Alarms use the alarm stream, so they're heard even with the phone on silent.
            audioAttributes = AudioAttributes.Builder()
                .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }
    }

    private fun vibrate(alarm: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(if (alarm) ALARM_PATTERN else SOUND_PATTERN, -1)
        if (Build.VERSION.SDK_INT >= 33) {
            val usage = if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
        } else {
            vibrator.vibrate(effect)
        }
    }

    private fun clear() {
        engine.reset()
        lastAnnounced.clear()
        _alerts.value = emptyList()
        session.setAlertAudible(emptySet())
        notifier.cancelAll()
    }

    companion object {
        /** Launch-intent extra from an alert notification: the camera to show full screen. */
        const val EXTRA_FOCUS_CAMERA = "app.nestview.extra.FOCUS_CAMERA"

        const val TICK_MS = 200L
        const val SNOOZE_MS = 10 * 60_000L
        const val ALARM_REPEAT_MS = 60_000L
        const val BATTERY_CHECK_MS = 10_000L
        private val SOUND_PATTERN = longArrayOf(0, 250, 150, 250)
        private val ALARM_PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)
    }
}
