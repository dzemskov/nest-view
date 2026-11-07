package app.nestview.core.analysis

import app.nestview.core.model.FeedStatus

data class HealthThresholds(
    /** Newest frame older than this: amber "DELAYED". */
    val delayedAfterMs: Long = 2_000,
    /** Newest frame older than this: red "FROZEN". */
    val frozenAfterMs: Long = 5_000,
    /** Newest frame older than this: drop the session and reconnect. */
    val reconnectAfterMs: Long = 10_000,
    /** Picture bit-identical for this long: "FROZEN" although frames arrive. */
    val identicalFrozenMs: Long = 30_000,
)

/** Pure rules that turn frame timing into a status, shared by every feed. */
object HealthEvaluator {

    /** Status of a running session, or null before its first frame. */
    fun pictureStatus(
        nowMs: Long,
        lastFrameAtMs: Long?,
        pictureUnchangedMs: Long,
        thresholds: HealthThresholds = HealthThresholds(),
    ): FeedStatus? {
        if (lastFrameAtMs == null) return null
        val age = nowMs - lastFrameAtMs
        return when {
            age >= thresholds.frozenAfterMs -> FeedStatus.FROZEN
            pictureUnchangedMs >= thresholds.identicalFrozenMs -> FeedStatus.FROZEN
            age >= thresholds.delayedAfterMs -> FeedStatus.DELAYED
            else -> FeedStatus.LIVE
        }
    }

    fun isStalled(nowMs: Long, lastFrameAtMs: Long?, thresholds: HealthThresholds = HealthThresholds()): Boolean =
        lastFrameAtMs != null && nowMs - lastFrameAtMs >= thresholds.reconnectAfterMs
}
