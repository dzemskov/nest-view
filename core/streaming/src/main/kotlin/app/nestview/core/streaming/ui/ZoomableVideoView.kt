package app.nestview.core.streaming.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import app.nestview.core.model.NormRect
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders a camera into a TextureView (so it composes with rounded corners, overlays and
 * animations). Zoom crops the decoded frame on the GPU before drawing, which keeps the picture
 * sharp up to the camera's own resolution instead of scaling up a view-sized image.
 */
@SuppressLint("ViewConstructor")
class ZoomableVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {

    private val renderer = EglRenderer("tile@${System.identityHashCode(this)}")
    private var initialized = false
    private var released = false

    /** Visible part of the picture, normalized and in display orientation. */
    @Volatile
    private var region: NormRect = NormRect.Full

    init {
        surfaceTextureListener = this
    }

    fun init(eglContext: EglBase.Context) {
        if (initialized || released) return
        renderer.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        initialized = true
        if (isAvailable) {
            surfaceTexture?.let { attachSurface(it, width, height) }
        }
    }

    fun setRegion(newRegion: NormRect) {
        region = newRegion
    }

    /** Frees GL resources; the view can't be used afterwards. */
    fun release() {
        if (released) return
        released = true
        if (initialized) renderer.release()
    }

    override fun onFrame(frame: VideoFrame) {
        if (!initialized || released) return
        val r = region
        if (r.isFull) {
            renderer.onFrame(frame)
            return
        }
        val cropped = crop(frame, r)
        try {
            renderer.onFrame(cropped)
        } finally {
            cropped.release()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized && !released) attachSurface(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized && !released) {
            val done = CountDownLatch(1)
            renderer.releaseEglSurface { done.countDown() }
            done.await(1, TimeUnit.SECONDS)
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun attachSurface(surface: SurfaceTexture, width: Int, height: Int) {
        if (height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height)
        renderer.createEglSurface(surface)
    }

    private fun crop(frame: VideoFrame, r: NormRect): VideoFrame {
        val buffer = frame.buffer
        val bw = buffer.width
        val bh = buffer.height
        // Map the display-space region into buffer space for the frame's rotation.
        val x: Float
        val y: Float
        val w: Float
        val h: Float
        when ((frame.rotation % 360 + 360) % 360) {
            90 -> { x = r.top; y = 1f - r.left - r.width; w = r.height; h = r.width }
            180 -> { x = 1f - r.left - r.width; y = 1f - r.top - r.height; w = r.width; h = r.height }
            270 -> { x = 1f - r.top - r.height; y = r.left; w = r.height; h = r.width }
            else -> { x = r.left; y = r.top; w = r.width; h = r.height }
        }
        val cw = ((w * bw).toInt() and 1.inv()).coerceIn(2, bw)
        val ch = ((h * bh).toInt() and 1.inv()).coerceIn(2, bh)
        val cx = ((x * bw).toInt() and 1.inv()).coerceIn(0, bw - cw)
        val cy = ((y * bh).toInt() and 1.inv()).coerceIn(0, bh - ch)
        val croppedBuffer = buffer.cropAndScale(cx, cy, cw, ch, cw, ch)
        return VideoFrame(croppedBuffer, frame.rotation, frame.timestampNs)
    }
}
