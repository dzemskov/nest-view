package app.nestview.core.analysis

import kotlin.random.Random

/**
 * Allows at most [limit] events in any rolling [windowMs]. A [reserve] keeps the last few slots
 * for more important callers (e.g. stream extensions over new streams).
 */
class SlidingWindowLimiter(private val limit: Int, private val windowMs: Long) {
    private val events = ArrayDeque<Long>()

    fun msUntilAvailable(nowMs: Long, reserve: Int = 0): Long {
        prune(nowMs)
        val allowed = (limit - reserve).coerceAtLeast(1)
        if (events.size < allowed) return 0
        // The slot frees when the oldest event that keeps us at the limit leaves the window.
        val blocking = events.elementAt(events.size - allowed)
        return (blocking + windowMs - nowMs).coerceAtLeast(1)
    }

    fun record(nowMs: Long) {
        prune(nowMs)
        events.addLast(nowMs)
    }

    fun count(nowMs: Long): Int {
        prune(nowMs)
        return events.size
    }

    private fun prune(nowMs: Long) {
        while (events.isNotEmpty() && events.first() <= nowMs - windowMs) events.removeFirst()
    }
}

/** Retry delays: 1, 2, 5, 10, 20, 30 s, then 30 s, each with up to [jitter] extra. */
class Backoff(
    private val stepsMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 20_000, 30_000),
    private val jitter: Double = 0.2,
) {
    fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        val base = stepsMs[attempt.coerceIn(0, stepsMs.lastIndex)]
        return base + (base * jitter * random.nextDouble()).toLong()
    }
}
