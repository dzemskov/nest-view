package app.nestview.feature.monitor

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nestview.core.designsystem.MonitorColors
import app.nestview.core.designsystem.OverlayIconButton
import app.nestview.core.designsystem.StatusPill
import app.nestview.core.designsystem.levelMeter
import app.nestview.core.designsystem.soundGlow
import app.nestview.core.model.FeedHealth
import app.nestview.core.model.FeedStatus
import app.nestview.core.model.NormRect
import app.nestview.core.model.ScaleMode
import app.nestview.core.model.ZoomMath
import app.nestview.core.model.ZoomWindow
import app.nestview.core.streaming.CameraFeed
import app.nestview.core.streaming.ui.ZoomableVideoView
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import kotlin.math.abs
import kotlin.math.roundToInt

private val TileShape = RoundedCornerShape(8.dp)
private val TileBackground = Color(0xFF0B0C0E)

@Composable
internal fun VideoTile(
    tile: TileState,
    eglContext: EglBase.Context,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onZoomCommitted: (ZoomWindow) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val health by tile.feed.health.collectAsStateWithLifecycle()
    val level = tile.feed.audioLevel.collectAsStateWithLifecycle()
    val lastFrame by tile.feed.lastFrame.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier
            .clip(TileShape)
            .background(TileBackground)
            .soundGlow({ level.value }),
    ) {
        val tileW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val tileH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val tileAspect = tileW / tileH
        val videoAspect = tile.aspect
        val fit = tile.scaleMode == ScaleMode.FIT
        val viewportAspect = if (fit) videoAspect else tileAspect

        var zoom by remember(tile.cameraId) { mutableStateOf(tile.zoom) }
        LaunchedEffect(tile.zoom) { zoom = tile.zoom }
        val region = ZoomMath.visibleRegion(zoom, videoAspect, viewportAspect)

        val videoModifier = if (fit) {
            Modifier
                .align(Alignment.Center)
                .aspectRatio(videoAspect, matchHeightConstraintsFirst = tileAspect > videoAspect)
        } else {
            Modifier.fillMaxSize()
        }
        VideoSurface(feed = tile.feed, eglContext = eglContext, region = region, modifier = videoModifier)

        if (!health.status.showsLivePicture) {
            StaleOverlay(lastFrame = lastFrame, health = health, modifier = Modifier.matchParentSize())
        }

        // Where the picture is drawn inside the tile (letterboxed in FIT mode).
        val viewportW = if (fit) (if (tileAspect > videoAspect) tileH * videoAspect else tileW) else tileW
        val viewportH = if (fit) (if (tileAspect > videoAspect) tileH else tileW / videoAspect) else tileH
        val viewportX = (tileW - viewportW) / 2f
        val viewportY = (tileH - viewportH) / 2f

        Box(
            Modifier
                .matchParentSize()
                .levelMeter({ level.value })
                .pointerInput(tile.cameraId) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { onDoubleTap() },
                    ) // holding a tile is handled by the grid: it opens the menu or starts a move

                }
                .pointerInput(tile.cameraId, tile.scaleMode, videoAspect, viewportAspect, viewportW, viewportH) {
                    detectZoomPan(
                        canPan = { zoom.isZoomed || !fit },
                        onGesture = { centroid, pan, factor ->
                            zoom = ZoomMath.applyGesture(
                                zoom = zoom,
                                videoAspect = videoAspect,
                                viewportAspect = viewportAspect,
                                centroidX = ((centroid.x - viewportX) / viewportW).coerceIn(0f, 1f),
                                centroidY = ((centroid.y - viewportY) / viewportH).coerceIn(0f, 1f),
                                zoomFactor = factor,
                                panX = pan.x / viewportW,
                                panY = pan.y / viewportH,
                            )
                        },
                        onEnd = { onZoomCommitted(zoom) },
                    )
                },
        )

        TileOverlay(tile = tile, status = health.status, zoom = zoom, controlsVisible = controlsVisible, onToggleMute = onToggleMute)
    }
}

@Composable
private fun VideoSurface(feed: CameraFeed, eglContext: EglBase.Context, region: NormRect, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember { ZoomableVideoView(context).apply { init(eglContext) } }
    DisposableEffect(view) { onDispose { view.release() } }
    // Draws only while the app is visible; in the background the stream keeps running for sound.
    LifecycleStartEffect(feed, view) {
        feed.addVideoSink(view)
        onStopOrDispose { feed.removeVideoSink(view) }
    }
    AndroidView(factory = { view }, update = { it.setRegion(region) }, modifier = modifier)
}

@Composable
private fun TileOverlay(
    tile: TileState,
    status: FeedStatus,
    zoom: ZoomWindow,
    controlsVisible: Boolean,
    onToggleMute: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        StatusPill(status = status, compact = !controlsVisible, modifier = Modifier.align(Alignment.TopStart))

        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tile.solo) Chip("SOLO")
            if (zoom.isZoomed && controlsVisible) Chip("${(zoom.scale * 10).roundToInt() / 10f}×")
            if (tile.muted && !controlsVisible) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Muted",
                    tint = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MonitorColors.ScrimStrong)
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
        }

        Text(
            tile.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .clip(RoundedCornerShape(50))
                .background(MonitorColors.Scrim)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        if (controlsVisible) {
            OverlayIconButton(
                icon = if (tile.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (tile.muted) "Unmute ${tile.title}" else "Mute ${tile.title}",
                onClick = onToggleMute,
                highlighted = tile.muted,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp),
            )
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MonitorColors.ScrimStrong)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Covers a picture that isn't live: greyed and blurred last image, status and its age. */
@Composable
private fun StaleOverlay(lastFrame: Bitmap?, health: FeedHealth, modifier: Modifier) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    Box(modifier.background(TileBackground), contentAlignment = Alignment.Center) {
        if (lastFrame != null) {
            val image = remember(lastFrame) { lastFrame.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = Grayscale,
                alpha = 0.45f,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(4.dp) else Modifier),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
            if (health.status == FeedStatus.CONNECTING || health.status == FeedStatus.RECONNECTING) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp, color = Color.White)
            }
            Text(
                statusText(health, now),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            health.lastFrameAtMs?.let { at ->
                Text("Last image ${formatAge(now - at)} ago", color = Color(0xCCFFFFFF), fontSize = 12.sp)
            }
        }
    }
}

private fun statusText(health: FeedHealth, now: Long): String = when (health.status) {
    FeedStatus.CONNECTING -> "Connecting…"
    FeedStatus.RECONNECTING -> health.nextRetryAtMs?.let { at ->
        val s = ((at - now) / 1000).coerceAtLeast(0)
        if (s > 0) "Connection lost · retrying in ${s}s" else "Reconnecting…"
    } ?: "Reconnecting…"
    FeedStatus.FROZEN -> "Picture frozen"
    FeedStatus.OFFLINE -> "Camera offline"
    FeedStatus.AUTH_REQUIRED -> "Google sign-in needed (Settings)"
    FeedStatus.STOPPED -> "Paused"
    FeedStatus.LIVE, FeedStatus.DELAYED -> ""
}

private fun formatAge(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%dh %02dm".format(s / 3600, (s % 3600) / 60)
}

/**
 * Pinch to zoom and drag to pan. One-finger drags are only taken when there is something to pan,
 * so taps, double taps and long presses keep working.
 */
private suspend fun PointerInputScope.detectZoomPan(
    canPan: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pastSlop = false
        var changed = false
        var zoomAccum = 1f
        var panAccum = Offset.Zero
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            val pointers = event.changes.count { it.pressed }
            if (!pastSlop) {
                zoomAccum *= zoomChange
                panAccum += panChange
                val zoomMotion = abs(1 - zoomAccum) * event.calculateCentroidSize(useCurrent = false)
                val panMotion = panAccum.getDistance()
                pastSlop = zoomMotion > viewConfiguration.touchSlop ||
                    (panMotion > viewConfiguration.touchSlop && (pointers > 1 || canPan()))
            }
            if (pastSlop) {
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    onGesture(event.calculateCentroid(useCurrent = false), panChange, zoomChange)
                    changed = true
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (changed) onEnd()
    }
}
