package app.nestview.feature.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.zIndex
import app.nestview.core.model.ZoomWindow
import app.nestview.feature.monitor.layout.Rearrange
import kotlinx.coroutines.launch
import kotlin.math.min
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nestview.core.designsystem.MonitorColors
import app.nestview.core.designsystem.OverlayIconButton
import app.nestview.core.model.LayoutSpec
import app.nestview.feature.monitor.background.ListeningService
import app.nestview.feature.monitor.layout.LayoutEngine
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import kotlin.math.roundToInt

private const val CONTROLS_TIMEOUT_MS = 4_000L

/**
 * The main screen: every camera of the current view, full screen, with the display kept on.
 * Tap shows or hides the controls, double-tap focuses a camera, hold opens its menu, and hold and
 * drag moves it (or takes it off the view).
 */
@Composable
fun MonitorScreen(onOpenSettings: () -> Unit, viewModel: MonitorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var interactions by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Taking a camera off the view is one drag away, so it's always undoable.
    fun removeCamera(id: String) {
        scope.launch {
            val removed = viewModel.removeCamera(id) ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = "${removed.name} removed from this view",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove(removed)
        }
    }

    KeepScreenOn()
    ImmersiveMode()
    BackHandler(enabled = state.focusedId != null) { viewModel.unfocus() }

    val hasTiles = state.tiles.isNotEmpty()
    BackgroundListening(
        enabled = state.keepListening && hasTiles,
        askForNotifications = !state.askedForNotifications,
        onAskedForNotifications = viewModel::markAskedForNotifications,
    )
    LaunchedEffect(state.controlsVisible, interactions, menuFor, adding, hasTiles) {
        if (state.controlsVisible && menuFor == null && !adding && hasTiles) {
            delay(CONTROLS_TIMEOUT_MS)
            viewModel.setControlsVisible(false)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = constraints.maxWidth >= constraints.maxHeight
        when {
            state.loading -> Unit
            state.tiles.isEmpty() -> EmptyMonitor(
                canAdd = state.addable.isNotEmpty(),
                onAddCamera = { adding = true },
                onOpenSettings = onOpenSettings,
            )
            else -> TileGrid(
                state = state,
                landscape = landscape,
                eglContext = viewModel.eglContext,
                onTap = {
                    viewModel.toggleControls()
                    interactions++
                },
                onDoubleTap = viewModel::focus,
                onOpenMenu = { menuFor = it },
                onLifted = { viewModel.setControlsVisible(false) },
                onReorder = { viewModel.reorder(it, landscape) },
                onRemove = ::removeCamera,
                onMoveBy = { id, delta -> viewModel.moveBy(id, delta, landscape) },
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
            onAddCamera = { adding = true },
            onToggleKeepListening = {
                val on = viewModel.toggleKeepListening()
                interactions++
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        if (on) {
                            "Keeps listening with the screen off. Stop it here or from the notification."
                        } else {
                            "Sound stops when you turn off the screen or leave the app."
                        },
                    )
                }
            },
        )

        AlertBanner(
            alerts = state.alerts,
            focusedId = state.focusedId,
            onShow = viewModel::showCamera,
            onSnooze = viewModel::snoozeAlert,
            onDismiss = viewModel::dismissAlert,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = if (state.controlsVisible && hasTiles) 72.dp else 8.dp),
        )

        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (state.controlsVisible) 88.dp else 8.dp),
        )

        if (adding) {
            AddCameraSheet(
                viewName = state.viewName,
                cameras = state.addable,
                snapshot = viewModel::snapshot,
                onAdd = {
                    viewModel.addCamera(it)
                    adding = false
                },
                onDismiss = { adding = false },
            )
        }

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
                    onRemove = {
                        menuFor = null
                        removeCamera(id)
                    },
                    onSoundAlert = { viewModel.setSoundAlert(id, it) },
                    onReconnect = {
                        viewModel.reconnect(id)
                        menuFor = null
                    },
                )
            }
        }
    }
}

/** A camera tile being moved: where it was picked up, where the finger is, and the order it would drop into. */
private data class TileDrag(
    val id: String,
    /** Finger position inside the tile when it was picked up. */
    val grab: Offset,
    val size: Size,
    val pointer: Offset,
    val startOrder: List<String>,
    val order: List<String>,
    val moved: Boolean = false,
    val overRemove: Boolean = false,
)

private val TileCorner = RoundedCornerShape(8.dp)

/**
 * Places the tiles and lets them be rearranged like icons on a home screen: hold a camera until it
 * lifts, drag it and the others make room, let go to drop it. A "Remove from view" target appears
 * at the top while a camera is lifted. Holding and letting go without moving opens its menu.
 */
@Composable
private fun TileGrid(
    state: MonitorUiState,
    landscape: Boolean,
    eglContext: EglBase.Context,
    onTap: () -> Unit,
    onDoubleTap: (String) -> Unit,
    onOpenMenu: (String) -> Unit,
    onLifted: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onMoveBy: (String, Int) -> Unit,
    onZoomCommitted: (String, ZoomWindow) -> Unit,
    onToggleMute: (String) -> Unit,
) {
    // Video may run under the status bar area, but not under a camera cutout.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        val density = LocalDensity.current
        val haptics = LocalHapticFeedback.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val spec = if (landscape) state.landscapeLayout else state.portraitLayout
        val gap = with(density) { 3.dp.toPx() }
        val ids = state.tiles.map { it.cameraId }
        val aspects = state.tiles.associate { it.cameraId to it.aspect }

        var drag by remember { mutableStateOf<TileDrag?>(null) }
        // After a drop, the new order (or the removal) stays on screen until it's saved, so tiles don't jump back.
        var settledOrder by remember { mutableStateOf<List<String>?>(null) }
        var removing by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(ids) {
            settledOrder = null
            removing = null
        }

        val shownIds = ids.filter { it != removing }
        val liveOrder = drag?.order ?: settledOrder?.takeIf { it.toSet() == shownIds.toSet() }
        val order = liveOrder ?: Rearrange.displayOrder(spec, shownIds)
        val layoutSpec = if (liveOrder != null) Rearrange.specFor(spec, order) else spec
        val rects = remember(layoutSpec, order, aspects, width, height, state.focusedId, gap) {
            LayoutEngine.compute(layoutSpec, order, aspects, width, height, gap, state.focusedId)
        }
        val removeZone = with(density) { Rect(width / 2f - 170.dp.toPx(), 0f, width / 2f + 170.dp.toPx(), 96.dp.toPx()) }

        // The gesture coroutine outlives compositions; it reads these through State.
        val currentRects by rememberUpdatedState(rects)
        val currentOrder by rememberUpdatedState(order)
        val layoutFor by rememberUpdatedState { o: List<String> ->
            LayoutEngine.compute(Rearrange.specFor(spec, o), o, aspects, width, height, gap)
        }
        val openMenu by rememberUpdatedState(onOpenMenu)
        val lifted by rememberUpdatedState(onLifted)
        val reorder by rememberUpdatedState(onReorder)
        val removeCallback by rememberUpdatedState(onRemove)

        fun remove(id: String) {
            removing = id
            removeCallback(id)
        }

        fun TileDrag.movedTo(p: Offset): TileDrag {
            val over = removeZone.contains(p)
            var next = order
            if (!over) {
                // Measured against the layout of the current drag order, not the last drawn frame.
                val target = Rearrange.tileAt(layoutFor(order), p.x, p.y)
                if (target != null && target != id) next = Rearrange.moveTo(order, id, target)
            }
            if (next != order) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            if (over && !overRemove) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            return copy(pointer = p, moved = true, order = next, overRemove = over)
        }

        val canArrange = state.focusedId == null
        val gestures = Modifier.pointerInput(canArrange) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // Watches before the tiles do (Initial pass), so it can take over once a camera lifts.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val id = Rearrange.tileAt(currentRects, down.position.x, down.position.y, inset = 0f) ?: return@awaitEachGesture
                var held = true
                val longPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (held) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        held = change != null && change.pressed && event.changes.count { it.pressed } == 1 &&
                            (change.position - down.position).getDistance() <= slop
                    }
                } == null
                if (!longPress) return@awaitEachGesture

                if (!canArrange) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    openMenu(id)
                    return@awaitEachGesture
                }
                val start = currentRects[id] ?: return@awaitEachGesture
                drag = TileDrag(
                    id = id,
                    grab = down.position - Offset(start.x, start.y),
                    size = Size(start.width, start.height),
                    pointer = down.position,
                    startOrder = currentOrder,
                    order = currentOrder,
                )
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                lifted()
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        val d = drag ?: break
                        if (d.moved || (change.position - down.position).getDistance() > slop) drag = d.movedTo(change.position)
                    }
                } finally {
                    val d = drag
                    drag = null
                    if (released && d != null) {
                        when {
                            !d.moved -> openMenu(d.id)
                            d.overRemove -> remove(d.id)
                            d.order != d.startOrder -> {
                                settledOrder = d.order
                                reorder(d.order)
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize().then(gestures)) {
            // Where the lifted camera will land.
            drag?.takeIf { it.moved && !it.overRemove }?.let { d ->
                rects[d.id]?.let { r ->
                    Box(
                        Modifier
                            .offset { IntOffset(r.x.roundToInt(), r.y.roundToInt()) }
                            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                            .background(Color.White.copy(alpha = 0.06f), TileCorner)
                            .border(2.dp, Color.White.copy(alpha = 0.45f), TileCorner),
                    )
                }
            }

            // Composed in a fixed order, whatever the view's order: moving a tile only changes its
            // rectangle, so its video surface is never torn down (that would blank the picture).
            val stableTiles = state.tiles.filter { it.cameraId != removing }.sortedBy { it.cameraId }
            for (tile in stableTiles) {
                key(tile.cameraId) {
                    val r = rects[tile.cameraId]
                    if (r != null) {
                        val d = drag?.takeIf { it.id == tile.cameraId }
                        val target = if (d != null) {
                            Rect(d.pointer - d.grab, d.size)
                        } else {
                            Rect(r.x, r.y, r.x + r.width, r.y + r.height)
                        }
                        val animated by animateRectAsState(
                            target,
                            animationSpec = if (d != null) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                            label = "tile",
                        )
                        val lift = when {
                            d == null -> 1f
                            d.overRemove -> 0.45f
                            else -> (0.55f * min(width / d.size.width, height / d.size.height)).coerceIn(0.4f, 0.92f)
                        }
                        val scale by animateFloatAsState(lift, label = "lift")
                        var origin by remember { mutableStateOf(TransformOrigin.Center) }
                        if (d != null) origin = TransformOrigin(d.grab.x / d.size.width, d.grab.y / d.size.height)
                        val id = tile.cameraId
                        VideoTile(
                            tile = tile,
                            eglContext = eglContext,
                            controlsVisible = state.controlsVisible && drag == null,
                            onTap = onTap,
                            onDoubleTap = { onDoubleTap(id) },
                            onZoomCommitted = { onZoomCommitted(id, it) },
                            onToggleMute = { onToggleMute(id) },
                            modifier = Modifier
                                .offset { IntOffset(animated.left.roundToInt(), animated.top.roundToInt()) }
                                .size(with(density) { animated.width.toDp() }, with(density) { animated.height.toDp() })
                                .zIndex(if (d != null) 1f else 0f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = origin
                                    shadowElevation = if (d != null) 24.dp.toPx() else 0f
                                    shape = TileCorner
                                    alpha = if (d?.overRemove == true) 0.7f else 1f
                                }
                                .then(
                                    if (d != null) {
                                        Modifier.border(3.dp, if (d.overRemove) MonitorColors.Problem else Color.White, TileCorner)
                                    } else {
                                        Modifier
                                    },
                                )
                                .semantics {
                                    // Screen readers go through the tiles in their on-screen order.
                                    traversalIndex = order.indexOf(id).toFloat()
                                    customActions = listOf(
                                        CustomAccessibilityAction("Move earlier") { onMoveBy(id, -1); true },
                                        CustomAccessibilityAction("Move later") { onMoveBy(id, 1); true },
                                        CustomAccessibilityAction("Remove from view") { remove(id); true },
                                    )
                                },
                        )
                    }
                }
            }

            RemoveTarget(
                visible = drag != null,
                active = drag?.overRemove == true,
                // Above the lifted camera (zIndex 1), so its label stays readable under the finger.
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f).padding(top = 16.dp),
            )
        }
    }
}

/** Where a lifted camera can be dropped to take it off the view. */
@Composable
private fun RemoveTarget(visible: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(), modifier = modifier) {
        val scale by animateFloatAsState(if (active) 1.12f else 1f, label = "remove")
        Row(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(if (active) MonitorColors.Problem else MonitorColors.ScrimStrong, RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (active) "Release to remove" else "Remove from view",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
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
    onAddCamera: () -> Unit,
    onToggleKeepListening: () -> Unit,
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
                OverlayIconButton(
                    icon = Icons.Default.Hearing,
                    contentDescription = if (state.keepListening) "Stop listening with the screen off" else "Keep listening with the screen off",
                    onClick = onToggleKeepListening,
                    highlighted = state.keepListening,
                    size = 56.dp,
                )
                LayoutMenuButton(current = if (landscape) state.landscapeLayout else state.portraitLayout, onLayout = onLayout)
                if (state.addable.isNotEmpty() && state.focusedId == null) {
                    OverlayIconButton(Icons.Default.Add, contentDescription = "Add a camera", onClick = onAddCamera, size = 56.dp)
                }
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
private fun EmptyMonitor(canAdd: Boolean, onAddCamera: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
        Text("No cameras on this view", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(
            if (canAdd) "Add the cameras you want to watch." else "Connect your cameras in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (canAdd) {
            Button(onClick = onAddCamera, modifier = Modifier.padding(top = 24.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add cameras")
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) { Text("Settings") }
        } else {
            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 24.dp)) { Text("Open settings") }
        }
    }
}

/**
 * While "Keep listening" is on, starts the listening service whenever the monitor is visible
 * (Android allows starting it only from the foreground), and asks once for notifications so its
 * Mute and Stop buttons can show.
 */
@Composable
private fun BackgroundListening(enabled: Boolean, askForNotifications: Boolean, onAskedForNotifications: () -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleStartEffect(enabled) {
        if (enabled) ListeningService.start(context)
        onStopOrDispose { }
    }
    LaunchedEffect(enabled, askForNotifications) {
        if (enabled && askForNotifications && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            onAskedForNotifications()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
