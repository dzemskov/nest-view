package app.nestview.feature.monitor.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.nestview.core.model.ActiveAlert
import app.nestview.core.model.AlertType
import app.nestview.feature.monitor.R
import app.nestview.feature.monitor.background.ListeningService

/**
 * Alert notifications for when the app is hidden (Keep listening on): heads-up, with sound and
 * vibration from their channel. Safety alarms use the alarm stream so a silenced phone still rings.
 */
internal class AlertNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val shown = HashSet<String>()

    fun createChannels() {
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_SOUND, "Sound alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A camera hears sound above its alert line"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setSound(sound, attributes(AudioAttributes.USAGE_NOTIFICATION_EVENT))
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Safety alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A camera stopped streaming, the internet dropped, or the battery is low"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(sound, attributes(AudioAttributes.USAGE_ALARM))
            },
        )
    }

    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
    fun show(alert: ActiveAlert, playsSound: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, if (alert.isAlarm) CHANNEL_ALARM else CHANNEL_SOUND)
            .setSmallIcon(R.drawable.ic_stat_listening)
            .setContentTitle(alert.title)
            .setContentText(text(alert, playsSound))
            .setCategory(if (alert.isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openApp(alert))
        if (alert.type == AlertType.SOUND && alert.cameraId != null) {
            builder.addAction(0, "Snooze 10 min", serviceAction(ListeningService.ACTION_SNOOZE_ALERT, alert))
        }
        builder.addAction(0, "Dismiss", serviceAction(ListeningService.ACTION_DISMISS_ALERT, alert))
        manager.notify(TAG, alert.key.hashCode(), builder.build())
        shown += alert.key
    }

    /** Removes notifications of alerts that are no longer active. */
    fun retain(keys: Set<String>) {
        shown.filter { it !in keys }.forEach { key ->
            manager.cancel(TAG, key.hashCode())
            shown -= key
        }
    }

    fun cancelAll() = retain(emptySet())

    private fun text(alert: ActiveAlert, playsSound: Boolean): String = when (alert.type) {
        AlertType.SOUND -> if (playsSound) "Its sound is on. Tap to watch." else "Tap to watch."
        AlertType.CAMERA_LOST -> "No picture for a while. Nest View keeps trying to reconnect."
        AlertType.OFFLINE -> "The cameras can't be watched until the connection is back."
        AlertType.LOW_BATTERY -> "Plug in this device so monitoring doesn't stop."
    }

    private fun openApp(alert: ActiveAlert): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        // Delivered to the running monitor (onNewIntent) instead of starting a second one.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        alert.cameraId?.let { launch.putExtra(AlertCoordinator.EXTRA_FOCUS_CAMERA, it) }
        return PendingIntent.getActivity(
            context,
            alert.key.hashCode(),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceAction(action: String, alert: ActiveAlert): PendingIntent =
        PendingIntent.getService(
            context,
            (action + alert.key).hashCode(),
            Intent(context, ListeningService::class.java)
                .setAction(action)
                .putExtra(ListeningService.EXTRA_ALERT_KEY, alert.key)
                .putExtra(ListeningService.EXTRA_CAMERA_ID, alert.cameraId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun attributes(usage: Int) = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    companion object {
        private const val TAG = "alert"
        const val CHANNEL_SOUND = "sound_alerts"
        const val CHANNEL_ALARM = "safety_alarms"
    }
}
