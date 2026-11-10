package app.nestview.feature.monitor.background

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.nestview.feature.monitor.R
import app.nestview.feature.monitor.session.ListeningInfo
import app.nestview.feature.monitor.session.MonitorSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Keep listening": keeps the cameras streaming and their sound playing with the screen off or
 * another app in front. Started from the visible monitor while Keep listening is on (Android only
 * allows that from the foreground), shown as an ongoing "Listening" notification with Mute and
 * Stop, and stopped as soon as Keep listening is turned off.
 */
@AndroidEntryPoint
class ListeningService : Service() {

    @Inject lateinit var session: MonitorSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hold: AutoCloseable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var info = ListeningInfo(enabled = true, cameraNames = emptyList(), allMuted = false, silent = false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        hold = session.holdInBackground()
        acquireLocks()
        scope.launch {
            session.listeningInfo.collect { latest ->
                info = latest
                if (!latest.enabled || latest.cameraNames.isEmpty()) {
                    stopSelf()
                } else {
                    notifyIfAllowed(buildNotification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                session.setKeepListening(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> scope.launch { session.toggleAllMuted() }
        }
        // Every startForegroundService() call must be answered with startForeground().
        goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        hold?.close()
        hold = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun goForeground() {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    @SuppressLint("WakelockTimeout") // held exactly as long as the user wants to listen
    private fun acquireLocks() {
        // The CPU must keep decoding and playing with the screen off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NestView:listening")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        // Keeps Wi-Fi out of power save, which otherwise adds latency and drops packets overnight.
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NestView:listening")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun buildNotification(): Notification {
        val names = info.cameraNames.joinToString(", ").ifEmpty { "Cameras" }
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_listening)
            .setContentTitle(if (info.silent) "Watching, sound off" else "Listening")
            .setContentText(names)
            .setContentIntent(openApp)
            .addAction(0, if (info.allMuted) "Unmute" else "Mute all", servicePendingIntent(ACTION_TOGGLE_MUTE, 1))
            .addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 2))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ListeningService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @SuppressLint("MissingPermission") // checked by areNotificationsEnabled()
    private fun notifyIfAllowed(notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Nest View plays camera sound in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ListeningService"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.nestview.action.STOP_LISTENING"
        private const val ACTION_TOGGLE_MUTE = "app.nestview.action.TOGGLE_MUTE"

        /** Starts listening in the background. Call while the app is visible. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java))
            } catch (e: IllegalStateException) {
                // Android refuses when the app isn't visible any more; the next start tries again.
                Log.w(TAG, "Could not start background listening", e)
            }
        }
    }
}
