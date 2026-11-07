package app.nestview.core.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the device has a usable network; reconnects are retried as soon as it returns. */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
            }

            override fun onLost(network: Network) {
                _isOnline.value = currentlyOnline()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        })
    }

    private fun currentlyOnline(): Boolean {
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Keeps each camera's last snapshot on disk so the app can show it instantly on start. */
@Singleton
class LastFrameCache @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.cacheDir, "last_frames")

    fun load(cameraId: String): Bitmap? {
        val file = fileFor(cameraId)
        return if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null
    }

    fun save(cameraId: String, bitmap: Bitmap) {
        runCatching {
            dir.mkdirs()
            val target = fileFor(cameraId)
            val tmp = File(dir, target.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    private fun fileFor(cameraId: String) = File(dir, cameraId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")
}
