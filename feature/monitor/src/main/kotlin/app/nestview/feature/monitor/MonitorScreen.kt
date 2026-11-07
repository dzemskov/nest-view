package app.nestview.feature.monitor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nestview.core.designsystem.MonitorColors
import app.nestview.core.designsystem.OverlayIconButton
import app.nestview.core.model.LayoutSpec
import app.nestview.feature.monitor.layout.LayoutEngine
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import kotlin.math.roundToInt

private const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * The main screen: every camera of the current view, full screen, with the display kept on.
 * Tap shows or hides the controls, double-tap focuses a camera, long-press opens its menu.
 */
@Composable
fun MonitorScreen(onOpenSettings: () -> Unit, viewModel: MonitorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var interactions by remember { mutableIntStateOf(0) }

    KeepScreenOn()
    ImmersiveMode()
    BackHandler(enabled = state.focusedId != null) { viewModel.unfocus() }

    val hasTiles = state.tiles.isNotEmpty()
    LaunchedEffect(state.controlsVisible, interactions, menuFor, hasTiles) {
        if (state.controlsVisible && menuFor == null && hasTiles) {
            delay(CONTROLS_TIMEOUT_MS)
            viewModel.setControlsVisible(false)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = constraints.maxWidth >= constraints.maxHeight
        when {
            state.loading -> Unit
            state.tiles.isEmpty() -> EmptyMonitor(onOpenSettings)
            else -> TileGrid(
                state = state,
                landscape = landscape,
                eglContext = viewModel.eglContext,
                onTap = {
                    viewModel.toggleControls()
                    interactions++
                },
                onDoubleTap = viewModel::focus,
                onLongPress = { menuFor = it },
                onZoomCommitted = viewModel::setZoom,
                onToggleMute = {
                    viewModel.toggleMute(it)
                    interactions++
                },
            )
        }
        ControlsOverlay(
            visible = state.controlsVisible && state.tiles.isNotEmpty(),
            state = state,
            landscape = landscape,
            onOpenSettings = onOpenSettings,
            onToggleAllMuted = {
                viewModel.toggleAllMuted()
                interactions++
            },
            onLayout = { spec ->
                viewModel.setLayout(landscape, spec)
                interactions++
            },
            onUnfocus = viewModel::unfocus,
        )

        menuFor?.let { id ->
            val tile = state.tiles.firstOrNull { it.cameraId == id }
            if (tile == null) {
                menuFor = null
            } else {
                TileMenuSheet(
                    tile = tile,
                    onDismiss = { menuFor = null },
                    onPreviewVolume = { viewModel.previewVolume(id, it) },
                    onCommitVolume = { viewModel.commitVolume(id, it) },
                    onToggleMute = { viewModel.toggleMute(id) },
                    onToggleSolo = { viewModel.toggleSolo(id) },
                    onScaleMode = { viewModel.setScaleMode(id, it) },
                    onPinZoom = { viewModel.pinZoom(id) },
                    onResetZoom = { viewModel.resetZoom(id) },
                    onForgetPinnedZoom = { viewModel.forgetPinnedZoom(id) },
                    onMakePrimary = {
                        viewModel.makePrimary(id, landscape)
                        menuFor = null
                    },
                    onReconnect = {
                        viewModel.reconnect(id)
                        menuFor = null
                    },
                )
            }
        }
    }
}

@Composable
private fun TileGrid(
    state: MonitorUiState,
    landscape: Boolean,
    eglContext: EglBase.Context,
    onTap: () -> Unit,
    onDoubleTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onZoomCommitted: (String, app.nestview.core.model.ZoomWindow) -> Unit,
    onToggleMute: (String) -> Unit,
) {
    // Video may run under the status bar area, but not under a camera cutout.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val spec = if (landscape) state.landscapeLayout else state.portraitLayout
        val gap = with(density) { 3.dp.toPx() }
        val ids = state.tiles.map { it.cameraId }
        val aspects = state.tiles.associate { it.cameraId to it.aspect }
        val rects = remember(spec, ids, aspects, width, height, state.focusedId, gap) {
            LayoutEngine.compute(spec, ids, aspects, width, height, gap, state.focusedId)
        }
        for (tile in state.tiles) {
            key(tile.cameraId) {
                val r = rects[tile.cameraId]
                if (r != null) {
                    val animated by animateRectAsState(Rect(r.x, r.y, r.x + r.width, r.y + r.height), label = "tile")
                    VideoTile(
                        tile = tile,
                        eglContext = eglContext,
                        controlsVisible = state.controlsVisible,
                        onTap = onTap,
                        onDoubleTap = { onDoubleTap(tile.cameraId) },
                        onLongPress = { onLongPress(tile.cameraId) },
                        onZoomCommitted = { onZoomCommitted(tile.cameraId, it) },
                        onToggleMute = { onToggleMute(tile.cameraId) },
                        modifier = Modifier
                            .offset { IntOffset(animated.left.roundToInt(), animated.top.roundToInt()) }
                            .size(with(density) { animated.width.toDp() }, with(density) { animated.height.toDp() }),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlsOverlay(
    visible: Boolean,
    state: MonitorUiState,
    landscape: Boolean,
    onOpenSettings: () -> Unit,
    onToggleAllMuted: () -> Unit,
    onLayout: (LayoutSpec) -> Unit,
    onUnfocus: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.viewName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .background(MonitorColors.ScrimStrong, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
                if (state.isDemo) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "DEMO",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(MonitorColors.Delayed, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                OverlayIconButton(Icons.Default.Settings, contentDescription = "Settings", onClick = onOpenSettings)
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(MonitorColors.ScrimStrong, RoundedCornerShape(50))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayIconButton(
                    icon = if (state.allMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (state.allMuted) "Unmute all" else "Mute all",
                    onClick = onToggleAllMuted,
                    highlighted = state.allMuted,
                    size = 56.dp,
                )
                LayoutMenuButton(current = if (landscape) state.landscapeLayout else state.portraitLayout, onLayout = onLayout)
                if (state.focusedId != null) {
                    OverlayIconButton(Icons.Default.GridView, contentDescription = "Show all cameras", onClick = onUnfocus, size = 56.dp)
                }
            }
        }
    }
}

@Composable
private fun LayoutMenuButton(current: LayoutSpec, onLayout: (LayoutSpec) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OverlayIconButton(Icons.Default.Dashboard, contentDescription = "Layout", onClick = { open = true }, size = 56.dp)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = listOf(
                "Best fit (automatic)" to LayoutSpec.Auto,
                "Grid · 2 columns" to LayoutSpec.Grid(2),
                "Grid · 3 columns" to LayoutSpec.Grid(3),
                "One large + small" to LayoutSpec.Focus((current as? LayoutSpec.Focus)?.primaryId),
                "Side by side" to LayoutSpec.Row,
                "Stacked" to LayoutSpec.Column,
            )
            options.forEach { (label, spec) ->
                val selected = spec == current || (spec is LayoutSpec.Focus && current is LayoutSpec.Focus)
                DropdownMenuItem(
                    text = { Text(if (selected) "✓  $label" else label) },
                    onClick = {
                        open = false
                        onLayout(spec)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyMonitor(onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
        Text("No cameras to show", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Choose which cameras appear here in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 24.dp)) { Text("Open settings") }
    }
}

/** Keeps the display on while the monitor is visible. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Hides the system bars; a swipe from the edge shows them briefly. */
@Composable
private fun ImmersiveMode() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
