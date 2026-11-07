package app.nestview.core.analysis

import app.nestview.core.model.AudioLevel
import app.nestview.core.model.FeedStatus
import app.nestview.core.model.SoundActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SoundLevelAnalyzerTest {
    private val rate = 16_000

    private fun noise(seconds: Double, amplitude: Int, random: Random = Random(1)): ShortArray =
        ShortArray((rate * seconds).toInt()) { (random.nextInt(-amplitude, amplitude + 1)).toShort() }

    private fun tone(seconds: Double, amplitude: Int): ShortArray =
        ShortArray((rate * seconds).toInt()) { i -> (amplitude * sin(2 * PI * 450 * i / rate)).toInt().toShort() }

    @Test
    fun silenceIsQuiet() {
        var last = AudioLevel.Silent
        val a = SoundLevelAnalyzer { last = it }
        val s = ShortArray(rate)
        a.process(s, 0, s.size, 1, rate)
        assertEquals(AudioLevel.SILENCE_DB, last.levelDb, 0.01f)
        assertEquals(SoundActivity.QUIET, last.activity)
    }

    @Test
    fun fullScaleToneIsNearZeroDb() {
        var last = AudioLevel.Silent
        val a = SoundLevelAnalyzer { last = it }
        val s = tone(0.5, 32767)
        a.process(s, 0, s.size, 1, rate)
        assertEquals(-3f, last.levelDb, 0.5f) // RMS of a sine is -3 dB
    }

    @Test
    fun cryOverWhiteNoiseStandsOutFromFloor() {
        var last = AudioLevel.Silent
        val a = SoundLevelAnalyzer { last = it }
        val background = noise(20.0, 600) // steady white-noise machine
        a.process(background, 0, background.size, 1, rate)
        val floorAfterNoise = last.floorDb
        assertEquals(SoundActivity.QUIET, last.activity)

        val cry = tone(1.0, 12_000)
        a.process(cry, 0, cry.size, 1, rate)
        assertTrue("cry should be loud, was ${last.aboveFloorDb} dB above floor", last.activity == SoundActivity.LOUD)
        // A one-second cry barely moves the learned background.
        assertEquals(floorAfterNoise, last.floorDb, 1f)
    }

    @Test
    fun releaseIsGradual() {
        val levels = mutableListOf<AudioLevel>()
        val a = SoundLevelAnalyzer { levels += it }
        val loud = tone(0.5, 20_000)
        a.process(loud, 0, loud.size, 1, rate)
        val peak = levels.last().levelDb
        val quiet = ShortArray(rate / 10) // 100 ms of silence = 2 windows
        a.process(quiet, 0, quiet.size, 1, rate)
        // 24 dB/s release: about 2.4 dB lower after 100 ms, not silence.
        assertEquals(peak - 2.4f, levels.last().levelDb, 0.2f)
    }
}

class FreezeDetectorTest {
    @Test
    fun changingPictureNeverFreezes() {
        val d = FreezeDetector()
        for (t in 0 until 60) d.onThumbnail(byteArrayOf(t.toByte(), 1, 2), t * 1_000L)
        assertEquals(0L, d.unchangedForMs(59_000))
        assertEquals(1_000L, d.unchangedForMs(60_000))
    }

    @Test
    fun identicalPictureCountsUp() {
        val d = FreezeDetector()
        val same = byteArrayOf(5, 6, 7)
        for (t in 0..40) d.onThumbnail(same, t * 1_000L)
        assertEquals(40_000L, d.unchangedForMs(40_000))
        d.reset()
        assertEquals(0L, d.unchangedForMs(41_000))
    }
}

class HealthEvaluatorTest {
    private val t = HealthThresholds()

    @Test
    fun noFrameYetHasNoPictureStatus() {
        assertNull(HealthEvaluator.pictureStatus(1_000, null, 0, t))
    }

    @Test
    fun statusFollowsFrameAge() {
        assertEquals(FeedStatus.LIVE, HealthEvaluator.pictureStatus(10_500, 10_000, 0, t))
        assertEquals(FeedStatus.DELAYED, HealthEvaluator.pictureStatus(12_500, 10_000, 0, t))
        assertEquals(FeedStatus.FROZEN, HealthEvaluator.pictureStatus(15_000, 10_000, 0, t))
        assertFalse(HealthEvaluator.isStalled(19_999, 10_000, t))
        assertTrue(HealthEvaluator.isStalled(20_000, 10_000, t))
    }

    @Test
    fun identicalPictureIsFrozenEvenWithFreshFrames() {
        assertEquals(FeedStatus.FROZEN, HealthEvaluator.pictureStatus(100_000, 99_900, 30_000, t))
    }
}

class RateLimitTest {
    @Test
    fun slidingWindowAllowsLimitPerWindow() {
        val l = SlidingWindowLimiter(limit = 3, windowMs = 60_000)
        repeat(3) { assertEquals(0L, l.msUntilAvailable(0)); l.record(0) }
        assertEquals(60_000L, l.msUntilAvailable(0))
        assertEquals(0L, l.msUntilAvailable(60_000))
    }

    @Test
    fun reserveKeepsSlotsForImportantCalls() {
        val l = SlidingWindowLimiter(limit = 4, windowMs = 60_000)
        l.record(0); l.record(1_000)
        assertTrue(l.msUntilAvailable(2_000, reserve = 2) > 0)
        assertEquals(0L, l.msUntilAvailable(2_000, reserve = 0))
        assertEquals(58_000L, l.msUntilAvailable(2_000, reserve = 2))
    }

    @Test
    fun backoffGrowsAndCaps() {
        val b = Backoff(jitter = 0.0)
        assertEquals(1_000L, b.delayFor(0))
        assertEquals(5_000L, b.delayFor(2))
        assertEquals(30_000L, b.delayFor(99))
    }
}
