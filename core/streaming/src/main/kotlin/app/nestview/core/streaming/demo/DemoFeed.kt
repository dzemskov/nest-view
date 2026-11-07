package app.nestview.core.streaming.demo

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import app.nestview.core.analysis.HealthEvaluator
import app.nestview.core.analysis.SoundLevelAnalyzer
import app.nestview.core.model.AudioLevel
import app.nestview.core.model.DemoCameras
import app.nestview.core.model.FeedHealth
import app.nestview.core.model.FeedStatus
import app.nestview.core.model.StreamStats
import app.nestview.core.model.VideoSize
import app.nestview.core.streaming.ManagedFeed
import app.nestview.core.streaming.frames.FrameTap
import app.nestview.core.streaming.frames.VideoFanout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthetic camera for trying the app without Nest cameras and for development: a dim nursery
 * with a slowly moving "baby", a white-noise hiss, and a short cry every minute or two.
 */
internal class DemoFeed(
    override val cameraId: String,
    private val scope: CoroutineScope,
) : ManagedFeed {

    private val variant = DemoCameras.variantOf(cameraId)
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val fanout = VideoFanout()
    private val _health = MutableStateFlow(FeedHealth(status = FeedStatus.STOPPED))
    private val _audioLevel = MutableStateFlow(AudioLevel.Silent)
    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    private val _lastFrame = MutableStateFlow<Bitmap?>(null)
    private val analyzer = SoundLevelAnalyzer { _audioLevel.value = it }
    @Volatile private var lastFrameAt: Long? = null
    @Volatile private var gain = 1f
    private var job: Job? = null

    private val tap = FrameTap(
        clock = clock,
        downstream = fanout,
        listener = object : FrameTap.Listener {
            override fun onFrame(nowMs: Long) {
                lastFrameAt = nowMs
            }
            override fun onVideoSize(size: VideoSize) {
                _videoSize.value = size
            }
            override fun onThumbnail(luma: ByteArray, nowMs: Long) = Unit
            override fun onSnapshot(bitmap: Bitmap) {
                _lastFrame.value = bitmap
            }
        },
    )

    override val health: StateFlow<FeedHealth> = _health.asStateFlow()
    override val audioLevel: StateFlow<AudioLevel> = _audioLevel.asStateFlow()
    override val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()
    override val lastFrame: StateFlow<Bitmap?> = _lastFrame.asStateFlow()

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            _health.value = _health.value.copy(status = FeedStatus.CONNECTING)
            delay(700L + variant * 350L) // connect like a real camera would
            lastFrameAt = null
            launch(Dispatchers.Default) { videoLoop() }
            launch(Dispatchers.IO) { audioLoop() }
            var liveSince: Long? = null
            while (isActive) {
                val now = clock()
                val last = lastFrameAt
                if (last != null && liveSince == null) liveSince = now
                _health.value = FeedHealth(
                    status = HealthEvaluator.pictureStatus(now, last, 0) ?: FeedStatus.CONNECTING,
                    lastFrameAtMs = last,
                    liveSinceMs = liveSince,
                    stats = StreamStats(width = WIDTH, height = HEIGHT, fps = 1000.0 / FRAME_MS, bitrateKbps = 0.0),
                )
                delay(500)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _health.value = _health.value.copy(status = FeedStatus.STOPPED)
        _audioLevel.value = AudioLevel.Silent
    }

    override fun reconnect() {
        stop()
        start()
    }

    override fun addVideoSink(sink: VideoSink) = fanout.add(sink)
    override fun removeVideoSink(sink: VideoSink) = fanout.remove(sink)
    override fun setAudioGain(gain: Float) {
        this.gain = gain
    }

    private suspend fun videoLoop() {
        val picture = DemoPicture(variant)
        val start = clock()
        while (true) {
            val buffer = JavaI420Buffer.allocate(WIDTH, HEIGHT)
            picture.draw(buffer, clock() - start)
            val frame = VideoFrame(buffer, 0, System.nanoTime())
            try {
                tap.onFrame(frame)
            } finally {
                frame.release()
            }
            delay(FRAME_MS)
        }
    }

    private suspend fun audioLoop() {
        val rate = 16_000
        val chunk = 320 // 20 ms
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, chunk * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val random = Random(variant * 7919 + 1)
        val raw = ShortArray(chunk)
        val out = ShortArray(chunk)
        var brown = 0.0
        var phase = 0.0
        var cryStart = clock() + 15_000L + variant * 12_000L
        var cryEnd = cryStart + 5_000L
        try {
            track.play()
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val now = clock()
                for (i in 0 until chunk) {
                    brown = (brown + random.nextDouble(-1.0, 1.0) * 0.06) * 0.995
                    var s = brown * 2_500.0 // steady hiss, like a white-noise machine
                    val t = now + i * 1000L / rate
                    if (t in cryStart until cryEnd) {
                        val tt = (t - cryStart) / 1000.0
                        val cycle = tt % 0.9
                        val envelope = if (cycle < 0.6) sin(PI * cycle / 0.6) else 0.0
                        val freq = 430.0 + 60.0 * sin(2 * PI * 5 * tt)
                        phase = (phase + freq / rate) % 1.0
                        s += envelope * 9_000.0 * (2.0 * phase - 1.0)
                    }
                    raw[i] = s.toInt().coerceIn(-32768, 32767).toShort()
                }
                analyzer.process(raw, 0, chunk, 1, rate)
                val g = gain
                for (i in 0 until chunk) out[i] = (raw[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                track.write(out, 0, chunk) // blocks, pacing the loop in real time
                if (now >= cryEnd) {
                    cryStart = now + random.nextLong(60_000, 120_000)
                    cryEnd = cryStart + random.nextLong(3_000, 7_000)
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            _audioLevel.value = AudioLevel.Silent
        }
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 270
        const val FRAME_MS = 66L
    }
}

/** Draws the demo picture straight into an I420 buffer. */
private class DemoPicture(private val variant: Int) {
    private var seed = 0x1234567 + variant * 31
    private val tintU = intArrayOf(0, -6, 4, 8)[variant % 4]
    private val tintV = intArrayOf(0, 8, -4, 4)[variant % 4]

    fun draw(buffer: JavaI420Buffer, elapsedMs: Long) {
        val w = buffer.width
        val h = buffer.height
        val y = buffer.dataY
        val strideY = buffer.strideY
        val t = elapsedMs / 1000.0
        val babyX = (w * (0.5 + 0.16 * sin(t * 0.21 + variant))).toInt()
        val babyY = (h * (0.6 + 0.08 * sin(t * 0.13 + variant * 2))).toInt()
        val babyR = h / 11
        val cribL = w / 5
        val cribR = w * 4 / 5
        val cribT = h * 3 / 10
        val cribB = h * 9 / 10
        val tick = ((elapsedMs / 1000) % 20).toInt() // a block that steps once a second: shows liveness
        for (row in 0 until h) {
            val base = 26 + row * 28 / h
            val dy = row - babyY
            val inCribRows = row in cribT..cribB
            for (col in 0 until w) {
                var v = base
                if (inCribRows && col in cribL..cribR &&
                    ((col - cribL) % 24 < 3 || row - cribT < 4 || cribB - row < 4)
                ) {
                    v = 92
                }
                val dx = col - babyX
                if (dx * dx + dy * dy < babyR * babyR) v = 150
                if (row in 8..15 && col in (12 + tick * 12)..(19 + tick * 12)) v = 120
                seed = seed * 1103515245 + 12345
                v += (seed ushr 28) - 8
                y.put(row * strideY + col, v.coerceIn(16, 235).toByte())
            }
        }
        fillPlane(buffer.dataU, buffer.strideU, (w + 1) / 2, (h + 1) / 2, 128 + tintU)
        fillPlane(buffer.dataV, buffer.strideV, (w + 1) / 2, (h + 1) / 2, 128 + tintV)
    }

    private fun fillPlane(plane: java.nio.ByteBuffer, stride: Int, w: Int, h: Int, value: Int) {
        val b = value.toByte()
        for (row in 0 until h) for (col in 0 until w) plane.put(row * stride + col, b)
    }
}
