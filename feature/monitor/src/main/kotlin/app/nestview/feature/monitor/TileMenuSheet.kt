package app.nestview.feature.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nestview.core.designsystem.MonitorColors
import app.nestview.core.designsystem.StatusPill
import app.nestview.core.model.AudioMix
import app.nestview.core.model.ScaleMode
import app.nestview.core.model.SoundAlertPrefs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TileMenuSheet(
    tile: TileState,
    onDismiss: () -> Unit,
    onPreviewVolume: (Float) -> Unit,
    onCommitVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onScaleMode: (ScaleMode) -> Unit,
    onPinZoom: () -> Unit,
    onResetZoom: () -> Unit,
    onForgetPinnedZoom: () -> Unit,
    onMakePrimary: () -> Unit,
    onRemove: () -> Unit,
    onSoundAlert: (SoundAlertPrefs) -> Unit,
    onReconnect: () -> Unit,
) {
    val health by tile.feed.health.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tile.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                StatusPill(health.status)
            }

            SectionTitle("Sound")
            var volume by remember(tile.cameraId) { mutableFloatStateOf(tile.volume) }
            Text("Volume ${(volume * 100).roundToInt()} %", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    onPreviewVolume(it)
                },
                onValueChangeFinished = {
                    volume = (volume * 20f).roundToInt() / 20f // snap to 5 %
                    onCommitVolume(volume)
                },
                valueRange = 0f..AudioMix.MAX_VOLUME,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tile.muted,
                    onClick = onToggleMute,
                    label = { Text(if (tile.muted) "Muted" else "Mute") },
                    leadingIcon = {
                        Icon(if (tile.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null)
                    },
                )
                FilterChip(
                    selected = tile.solo,
                    onClick = onToggleSolo,
                    label = { Text("Listen to this one only") },
                    leadingIcon = { Icon(Icons.Default.Headphones, null) },
                )
            }
            Text(
                "Muted cameras still show sound on screen: the border glows when there is noise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SoundAlertSection(tile = tile, onChange = onSoundAlert)

            HorizontalDivider()
            SectionTitle("Picture")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tile.scaleMode == ScaleMode.FIT, onClick = { onScaleMode(ScaleMode.FIT) }, label = { Text("Whole picture") })
                FilterChip(selected = tile.scaleMode == ScaleMode.FILL, onClick = { onScaleMode(ScaleMode.FILL) }, label = { Text("Fill the tile") })
            }
            Text(
                "Pinch to zoom and drag to move. Save a zoom (for example on the crib) to see it every time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPinZoom, enabled = tile.zoom.isZoomed && tile.zoom != tile.pinnedZoom) { Text("Save this zoom") }
                OutlinedButton(onClick = onResetZoom, enabled = tile.zoom.isZoomed) { Text("Zoom out") }
                if (tile.pinnedZoom != null) TextButton(onClick = onForgetPinnedZoom) { Text("Forget saved zoom") }
            }

            HorizontalDivider()
            SectionTitle("On this view")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMakePrimary) { Text("Show this camera large") }
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove from view") }
            }
            Text(
                "Tip: hold a camera and drag it to move it. Drop it on “Remove from view” at the top to take it off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Connection")
            val stats = health.stats
            if (stats != null && stats.width > 0) {
                Text(
                    "${stats.width}×${stats.height} · ${stats.fps.roundToInt()} fps · ${stats.bitrateKbps.roundToInt()} kbit/s · " +
                        "jitter ${stats.jitterMs.roundToInt()} ms · lost ${stats.packetsLost}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Reconnects since start: ${health.reconnects}", style = MaterialTheme.typography.bodySmall)
            health.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onReconnect) { Text("Reconnect now") }
            Spacer(Modifier.padding(4.dp))
        }
    }
}

/**
 * Turns this camera's sound alert on and sets its line. The slider's track is a live level bar,
 * so the line can be put just above the room's current noise.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SoundAlertSection(tile: TileState, onChange: (SoundAlertPrefs) -> Unit) {
    val prefs = tile.soundAlert
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SectionTitle("Sound alert")
            Text(
                if (prefs.enabled) "Alerts you when there's sound above the line." else "Off. Turn on to be alerted to crying or other sound, even when muted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = prefs.enabled, onCheckedChange = { onChange(prefs.copy(enabled = it)) })
    }
    if (!prefs.enabled) return

    val level by tile.feed.audioLevel.collectAsStateWithLifecycle()
    var line by remember(tile.cameraId) { mutableFloatStateOf(prefs.thresholdDb) }
    val now = level.aboveFloorDb
    Slider(
        value = line,
        onValueChange = { line = it.coerceIn(SoundAlertPrefs.MIN_THRESHOLD_DB, SoundAlertPrefs.MAX_THRESHOLD_DB) },
        onValueChangeFinished = { onChange(prefs.copy(thresholdDb = line.roundToInt().toFloat())) },
        valueRange = 0f..METER_MAX_DB,
        track = { LevelTrack(levelFraction = now / METER_MAX_DB, lineFraction = line / METER_MAX_DB) },
    )
    Text(
        "Now ${now.roundToInt()} dB above the room's usual noise · alert above ${line.roundToInt()} dB. " +
            "Make some noise near the camera to see where it lands.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Lasting at least", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoundAlertPrefs.SUSTAIN_CHOICES.forEach { seconds ->
            FilterChip(
                selected = prefs.sustainSeconds == seconds,
                onClick = { onChange(prefs.copy(sustainSeconds = seconds)) },
                label = { Text("$seconds s") },
            )
        }
    }
}

/** The live sound level (green, red once above the line), with the zone above the line tinted. */
@Composable
private fun LevelTrack(levelFraction: Float, lineFraction: Float) {
    val above = levelFraction >= lineFraction
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(14.dp),
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = radius)
        val lineX = size.width * lineFraction.coerceIn(0f, 1f)
        drawRect(MonitorColors.Problem.copy(alpha = 0.2f), topLeft = Offset(lineX, 0f), size = Size(size.width - lineX, size.height))
        drawRoundRect(
            color = if (above) MonitorColors.Problem else MonitorColors.Live,
            size = Size(size.width * levelFraction.coerceIn(0f, 1f), size.height),
            cornerRadius = radius,
        )
    }
}

/** The level bar's full scale, in dB above the room's usual noise. */
private const val METER_MAX_DB = 36f

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
