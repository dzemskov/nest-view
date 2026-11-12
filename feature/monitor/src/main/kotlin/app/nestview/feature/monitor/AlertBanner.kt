package app.nestview.feature.monitor

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nestview.core.designsystem.MonitorColors
import app.nestview.core.model.ActiveAlert
import app.nestview.core.model.AlertType
import kotlinx.coroutines.delay

/**
 * Alerts at the top of the monitor, newest first (two at most, then "+N more"). Each has Show (its
 * camera full screen), Snooze for sound alerts, and Dismiss.
 */
@Composable
internal fun AlertBanner(
    alerts: List<ActiveAlert>,
    focusedId: String?,
    onShow: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    AnimatedVisibility(
        visible = alerts.isNotEmpty(),
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier,
    ) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            alerts.take(2).forEach { alert ->
                AlertCard(
                    alert = alert,
                    now = now,
                    canShow = alert.cameraId != null && alert.cameraId != focusedId,
                    onShow = { alert.cameraId?.let(onShow) },
                    onSnooze = { alert.cameraId?.let(onSnooze) },
                    onDismiss = { onDismiss(alert.key) },
                )
            }
            if (alerts.size > 2) {
                Text(
                    "+${alerts.size - 2} more",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .background(MonitorColors.ScrimStrong, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AlertCard(
    alert: ActiveAlert,
    now: Long,
    canShow: Boolean,
    onShow: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = if (alert.isAlarm) MonitorColors.Problem else MonitorColors.Delayed
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF0181A1E), shape)
            .border(1.5.dp, accent, shape)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(alert.type), contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    alert.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(detail(alert, now), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (canShow) TextButton(onClick = onShow) { Text("Show") }
            if (alert.type == AlertType.SOUND) TextButton(onClick = onSnooze) { Text("Snooze 10 min") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun iconFor(type: AlertType) = when (type) {
    AlertType.SOUND -> Icons.AutoMirrored.Filled.VolumeUp
    AlertType.CAMERA_LOST -> Icons.Default.VideocamOff
    AlertType.OFFLINE -> Icons.Default.WifiOff
    AlertType.LOW_BATTERY -> Icons.Default.BatteryAlert
}

private fun detail(alert: ActiveAlert, now: Long): String = when (alert.type) {
    AlertType.SOUND -> {
        val quietFor = now - alert.lastSeenAtMs
        if (quietFor < 3_000) "Sound now · started ${age(now - alert.startedAtMs)} ago" else "Last heard ${age(quietFor)} ago"
    }
    AlertType.CAMERA_LOST -> "No picture for ${age(now - alert.startedAtMs)}. Still trying to reconnect."
    AlertType.OFFLINE -> "For ${age(now - alert.startedAtMs)}. The cameras can't be watched until it's back."
    AlertType.LOW_BATTERY -> "Plug it in so monitoring doesn't stop."
}

private fun age(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%dh %02dm".format(s / 3600, (s % 3600) / 60)
}
