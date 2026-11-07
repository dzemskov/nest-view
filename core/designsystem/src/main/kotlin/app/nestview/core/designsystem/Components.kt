package app.nestview.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nestview.core.model.AudioLevel
import app.nestview.core.model.FeedStatus
import app.nestview.core.model.SoundActivity

fun FeedStatus.color(): Color = when (this) {
    FeedStatus.LIVE -> MonitorColors.Live
    FeedStatus.DELAYED -> MonitorColors.Delayed
    FeedStatus.FROZEN, FeedStatus.AUTH_REQUIRED -> MonitorColors.Problem
    FeedStatus.CONNECTING, FeedStatus.RECONNECTING -> MonitorColors.Delayed
    FeedStatus.OFFLINE, FeedStatus.STOPPED -> MonitorColors.Offline
}

fun FeedStatus.label(): String = when (this) {
    FeedStatus.LIVE -> "LIVE"
    FeedStatus.DELAYED -> "DELAYED"
    FeedStatus.FROZEN -> "FROZEN"
    FeedStatus.CONNECTING -> "CONNECTING"
    FeedStatus.RECONNECTING -> "RECONNECTING"
    FeedStatus.OFFLINE -> "OFFLINE"
    FeedStatus.AUTH_REQUIRED -> "SIGN IN"
    FeedStatus.STOPPED -> "PAUSED"
}

/** Live/health indicator. When [compact] and live, it's just a small green dot. */
@Composable
fun StatusPill(status: FeedStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = status.color()
    if (compact && status == FeedStatus.LIVE) {
        Box(
            modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        return
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MonitorColors.ScrimStrong)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(status.label(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    }
}

fun SoundActivity.color(): Color? = when (this) {
    SoundActivity.QUIET -> null
    SoundActivity.SOFT -> MonitorColors.SoundSoft
    SoundActivity.NOTICEABLE -> MonitorColors.SoundNoticeable
    SoundActivity.LOUD -> MonitorColors.SoundLoud
}

/**
 * A border that lights up with sound: green, then amber, then red as the sound rises above the
 * room's background. Reads the level during drawing only, so 20 updates a second cost no recomposition.
 */
fun Modifier.soundGlow(level: () -> AudioLevel, cornerRadius: Dp = 8.dp, width: Dp = 4.dp): Modifier =
    drawWithContent {
        drawContent()
        val current = level()
        val color = current.activity.color() ?: return@drawWithContent
        val strength = ((current.aboveFloorDb - 6f) / 20f).coerceIn(0.35f, 1f)
        val stroke = width.toPx()
        drawRoundRect(
            color = color.copy(alpha = strength),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = Stroke(width = stroke),
        )
    }

/** A thin vertical level meter with a tick for the learned background level. */
fun Modifier.levelMeter(level: () -> AudioLevel, barWidth: Dp = 5.dp, margin: Dp = 6.dp): Modifier =
    drawWithContent {
        drawContent()
        val current = level()
        val w = barWidth.toPx()
        val m = margin.toPx()
        val trackHeight = size.height * 0.5f
        val left = size.width - m - w
        val bottom = size.height * 0.75f
        drawRoundRect(
            color = Color(0x55000000),
            topLeft = Offset(left, bottom - trackHeight),
            size = Size(w, trackHeight),
            cornerRadius = CornerRadius(w / 2),
        )
        val filled = trackHeight * current.meter
        if (filled > 0.5f) {
            drawRoundRect(
                color = current.activity.color() ?: Color(0xCCFFFFFF),
                topLeft = Offset(left, bottom - filled),
                size = Size(w, filled),
                cornerRadius = CornerRadius(w / 2),
            )
        }
        if (current.floorDb > AudioLevel.SILENCE_DB + 1) {
            val y = bottom - trackHeight * current.floorMeter
            drawLine(Color(0xAAFFFFFF), Offset(left - 2, y), Offset(left + w + 2, y), strokeWidth = 1.5f)
        }
    }

/** A round, translucent icon button that stays readable on any picture. */
@Composable
fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Color.White,
    highlighted: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (highlighted) Color(0xCC8AB4F8) else MonitorColors.Scrim,
            contentColor = if (highlighted) Color.Black else tint,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.5f))
    }
}
