package app.nestview.feature.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nestview.core.designsystem.StatusPill
import app.nestview.core.model.AudioMix
import app.nestview.core.model.ScaleMode
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
            TextButton(onClick = onMakePrimary) { Text("Show this camera large") }

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

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
