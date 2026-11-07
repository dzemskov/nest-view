package app.nestview.core.analysis

import app.nestview.core.model.AudioLevel
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns 16-bit PCM into smoothed levels in dBFS, about [windowMs] apart.
 *
 * - Attack is instant and release is limited to [releaseDbPerSecond], so short sounds stay
 *   visible long enough to be seen.
 * - The background level ("floor") follows quiet periods quickly and loud periods very slowly,
 *   so a steady white-noise machine becomes the floor while crying stays far above it.
 *
 * Not thread-safe: feed it from one thread at a time.
 */
class SoundLevelAnalyzer(
    private val windowMs: Int = 50,
    private val releaseDbPerSecond: Float = 24f,
    private val onLevel: (AudioLevel) -> Unit,
) {
    private var sumSquares = 0.0
    private var peak = 0
    private var valuesInWindow = 0
    private var smoothedDb = AudioLevel.SILENCE_DB
    private var floorDb = Float.NaN

    fun process(samples: ShortBuffer, valueCount: Int, channels: Int, sampleRate: Int) {
        val windowValues = max(1, sampleRate * windowMs / 1000) * max(1, channels)
        val start = samples.position()
        for (i in 0 until valueCount) {
            add(samples.get(start + i).toInt(), windowValues)
        }
    }

    fun process(samples: ShortArray, offset: Int, valueCount: Int, channels: Int, sampleRate: Int) {
        val windowValues = max(1, sampleRate * windowMs / 1000) * max(1, channels)
        for (i in offset until offset + valueCount) {
            add(samples[i].toInt(), windowValues)
        }
    }

    fun reset() {
        sumSquares = 0.0
        peak = 0
        valuesInWindow = 0
        smoothedDb = AudioLevel.SILENCE_DB
        floorDb = Float.NaN
    }

    private fun add(sample: Int, windowValues: Int) {
        sumSquares += sample.toDouble() * sample
        val a = abs(sample)
        if (a > peak) peak = a
        valuesInWindow++
        if (valuesInWindow >= windowValues) emitWindow()
    }

    private fun emitWindow() {
        val rms = sqrt(sumSquares / valuesInWindow)
        val db = toDb(rms)
        val peakDb = toDb(peak.toDouble())
        val dt = windowMs / 1000f

        smoothedDb = if (db >= smoothedDb) db else max(db, smoothedDb - releaseDbPerSecond * dt)

        val floor = floorDb
        floorDb = when {
            floor.isNaN() -> db
            db < floor -> floor + (db - floor) * FLOOR_FALL
            db - floor <= STEADY_RANGE_DB -> floor + (db - floor) * FLOOR_RISE_STEADY
            else -> floor + (db - floor) * FLOOR_RISE_EVENT
        }

        onLevel(AudioLevel(levelDb = smoothedDb, peakDb = peakDb, floorDb = floorDb))

        sumSquares = 0.0
        peak = 0
        valuesInWindow = 0
    }

    companion object {
        // Per 50 ms window: falls over about a second (so brief dips in fluctuating noise don't
        // drag it down), rises over ~2 min for steady sound and ~8 min for sound far above the
        // floor (so long crying stays "loud").
        private const val FLOOR_FALL = 0.05f
        private const val FLOOR_RISE_STEADY = 0.0005f
        private const val FLOOR_RISE_EVENT = 0.0001f
        private const val STEADY_RANGE_DB = 6f

        fun toDb(amplitude: Double): Float {
            if (amplitude <= 0.0) return AudioLevel.SILENCE_DB
            return (20.0 * log10(amplitude / 32768.0)).toFloat().coerceAtLeast(AudioLevel.SILENCE_DB)
        }
    }
}
