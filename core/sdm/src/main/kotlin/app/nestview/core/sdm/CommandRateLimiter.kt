package app.nestview.core.sdm

import android.os.SystemClock
import app.nestview.core.analysis.SlidingWindowLimiter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

enum class CommandPriority {
    /** Keeps a running stream alive: may use the reserved slots. */
    EXTEND,

    /** Starts a stream. */
    GENERATE,

    /** Frees a stream early: skipped when no slot is free (the session expires anyway). */
    STOP,
}

/**
 * Keeps camera commands under Google's limits: 10 per minute per user across all devices,
 * 5 per minute per device and command, 100 per hour per camera. Leaves headroom and keeps two
 * slots for extensions so reconnect storms can't starve running streams.
 */
@Singleton
class CommandRateLimiter(private val clock: () -> Long) {

    @Inject
    constructor() : this({ SystemClock.elapsedRealtime() })

    private val mutex = Mutex()
    private val perUser = SlidingWindowLimiter(limit = 8, windowMs = 60_000)
    private val perDevice = HashMap<String, DeviceLimits>()

    private class DeviceLimits {
        val minute = SlidingWindowLimiter(limit = 4, windowMs = 60_000)
        val hour = SlidingWindowLimiter(limit = 90, windowMs = 3_600_000)
    }

    /** Waits for a free slot. Returns false only for [CommandPriority.STOP] when none is free now. */
    suspend fun acquire(deviceId: String, priority: CommandPriority): Boolean {
        while (true) {
            val waitMs = mutex.withLock {
                val now = clock()
                val device = perDevice.getOrPut(deviceId) { DeviceLimits() }
                val reserve = if (priority == CommandPriority.EXTEND) 0 else RESERVED_FOR_EXTEND
                val wait = max(
                    perUser.msUntilAvailable(now, reserve),
                    max(device.minute.msUntilAvailable(now), device.hour.msUntilAvailable(now)),
                )
                if (wait == 0L) {
                    perUser.record(now)
                    device.minute.record(now)
                    device.hour.record(now)
                    return true
                }
                if (priority == CommandPriority.STOP) return false
                wait
            }
            delay(waitMs.coerceAtLeast(50))
        }
    }

    private companion object {
        const val RESERVED_FOR_EXTEND = 2
    }
}
