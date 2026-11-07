package app.nestview.core.streaming.frames

import android.graphics.Bitmap
import android.graphics.Matrix
import app.nestview.core.model.VideoSize
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CopyOnWriteArraySet

/** Sends each frame to every attached renderer. */
internal class VideoFanout : VideoSink {
    private val sinks = CopyOnWriteArraySet<VideoSink>()

    fun add(sink: VideoSink) {
        sinks.add(sink)
    }

    fun remove(sink: VideoSink) {
        sinks.remove(sink)
    }

    override fun onFrame(frame: VideoFrame) {
        for (sink in sinks) sink.onFrame(frame)
    }
}

/**
 * Watches frames on their way to the renderers: frame timing for the health watchdog, a tiny luma
 * thumbnail every second for freeze detection, and a small colour snapshot every few seconds for
 * the "last image" placeholder.
 */
internal class FrameTap(
    private val clock: () -> Long,
    private val downstream: VideoSink,
    private val listener: Listener,
) : VideoSink {

    interface Listener {
        fun onFrame(nowMs: Long)
        fun onVideoSize(size: VideoSize)
        fun onThumbnail(luma: ByteArray, nowMs: Long)
        fun onSnapshot(bitmap: Bitmap)
    }

    private var lastThumbAt = Long.MIN_VALUE / 2
    private var lastSnapshotAt = Long.MIN_VALUE / 2
    private var lastWidth = 0
    private var lastHeight = 0

    override fun onFrame(frame: VideoFrame) {
        val now = clock()
        listener.onFrame(now)
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w
            lastHeight = h
            listener.onVideoSize(VideoSize(w, h))
        }
        if (now - lastThumbAt >= THUMB_INTERVAL_MS) {
            lastThumbAt = now
            val snapshot = now - lastSnapshotAt >= SNAPSHOT_INTERVAL_MS
            if (snapshot) lastSnapshotAt = now
            runCatching { capture(frame, now, snapshot) }
        }
        downstream.onFrame(frame)
    }

    private fun capture(frame: VideoFrame, now: Long, snapshot: Boolean) {
        val buffer = frame.buffer
        if (buffer.width <= 0 || buffer.height <= 0) return
        val tw = THUMB_WIDTH
        val th = (THUMB_WIDTH * buffer.height / buffer.width).coerceAtLeast(2) and 1.inv()
        val scaled = buffer.cropAndScale(0, 0, buffer.width, buffer.height, tw, th)
        try {
            val i420 = scaled.toI420() ?: return
            try {
                val y = i420.dataY
                val strideY = i420.strideY
                val luma = ByteArray(tw * th)
                for (row in 0 until th) {
                    for (col in 0 until tw) luma[row * tw + col] = y.get(row * strideY + col)
                }
                listener.onThumbnail(luma, now)
                if (snapshot) listener.onSnapshot(toBitmap(i420, tw, th, frame.rotation))
            } finally {
                i420.release()
            }
        } finally {
            scaled.release()
        }
    }

    private fun toBitmap(i420: VideoFrame.I420Buffer, w: Int, h: Int, rotation: Int): Bitmap {
        val y = i420.dataY
        val u = i420.dataU
        val v = i420.dataV
        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yy = (y.get(row * i420.strideY + col).toInt() and 0xff) - 16
                val uu = (u.get((row / 2) * i420.strideU + col / 2).toInt() and 0xff) - 128
                val vv = (v.get((row / 2) * i420.strideV + col / 2).toInt() and 0xff) - 128
                val c = 298 * yy
                val r = ((c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((c + 516 * uu + 128) shr 8).coerceIn(0, 255)
                pixels[row * w + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (rotation % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
    }

    private companion object {
        const val THUMB_WIDTH = 160
        const val THUMB_INTERVAL_MS = 1_000L
        const val SNAPSHOT_INTERVAL_MS = 5_000L
    }
}
