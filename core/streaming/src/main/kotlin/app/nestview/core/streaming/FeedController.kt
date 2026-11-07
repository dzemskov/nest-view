package app.nestview.core.streaming

import app.nestview.core.analysis.Backoff
import app.nestview.core.analysis.FreezeDetector
import app.nestview.core.analysis.HealthEvaluator
import app.nestview.core.analysis.HealthThresholds
import app.nestview.core.model.FeedHealth
import app.nestview.core.model.FeedStatus
import app.nestview.core.model.StreamApiException
import app.nestview.core.model.StreamStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

enum class SessionConnection { CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/**
 * One streaming session with a camera. Its video and audio reach the feed only while active, so
 * a replacement session can warm up in the background and take over without a visible gap.
 */
interface StreamSession {
    val id: String
    val initialExpiresInMs: Long
    val connection: StateFlow<SessionConnection>
    val framesReceived: Long

    /** Returns the new remaining lifetime. */
    suspend fun extend(): Long
    fun setActive(active: Boolean)
    fun setAudioGain(gain: Float)
    suspend fun stats(): StreamStats?
    suspend fun close()
}

fun interface StreamSessionFactory {
    suspend fun open(cameraId: String): StreamSession
}

data class FeedTiming(
    val connectTimeoutMs: Long = 25_000,
    val firstFrameTimeoutMs: Long = 20_000,
    /** Extend this long before the session expires. */
    val extendLeadMs: Long = 60_000,
    val extendRetryMs: Long = 10_000,
    /** An extension that adds less than this was ignored (battery camera). */
    val minExtensionGainMs: Long = 60_000,
    /** Battery cameras: open the replacement session this long before expiry. */
    val rolloverLeadMs: Long = 45_000,
    val healthTickMs: Long = 500,
    val statsIntervalMs: Long = 2_000,
    val disconnectedGraceMs: Long = 4_000,
    /** A session that was live this long resets the backoff. */
    val stableAfterMs: Long = 60_000,
    val rateLimitedWaitMs: Long = 60_000,
    val offlineRetryMs: Long = 30_000,
    /** At most one reconnect per this period for a picture that stopped changing. */
    val identicalReconnectCooldownMs: Long = 10 * 60_000L,
    val thresholds: HealthThresholds = HealthThresholds(),
)

/**
 * Keeps one camera streaming: connects, extends sessions before they expire, rolls battery
 * cameras over to a fresh session, watches health and reconnects with backoff.
 *
 * Holds no WebRTC types, so it runs in plain unit tests with fake sessions and virtual time.
 */
class FeedController(
    private val cameraId: String,
    private val factory: StreamSessionFactory,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val networkAvailable: StateFlow<Boolean>,
    private val timing: FeedTiming = FeedTiming(),
    private val backoff: Backoff = Backoff(),
    private val random: Random = Random.Default,
) {
    private val _health = MutableStateFlow(FeedHealth(status = FeedStatus.STOPPED))
    val health: StateFlow<FeedHealth> = _health.asStateFlow()

    @Volatile private var lastFrameAtMs: Long? = null
    @Volatile private var activeSession: StreamSession? = null
    @Volatile private var audioGain = 1f
    @Volatile private var extensionsIgnored = false
    private val freeze = FreezeDetector()
    private val reconnectRequests = Channel<Unit>(Channel.CONFLATED)
    private var lastIdenticalReconnectAt = Long.MIN_VALUE / 2
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _health.update { it.copy(status = FeedStatus.CONNECTING, message = null) }
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _health.update { it.copy(status = FeedStatus.STOPPED, nextRetryAtMs = null, message = null) }
    }

    fun requestReconnect() {
        reconnectRequests.trySend(Unit)
    }

    fun setAudioGain(gain: Float) {
        audioGain = gain
        activeSession?.setAudioGain(gain)
    }

    /** Called for every frame of the active session. */
    fun onFrame(nowMs: Long) {
        lastFrameAtMs = nowMs
    }

    /** Called about once a second with a tiny luma thumbnail of the active session. */
    fun onThumbnail(luma: ByteArray, nowMs: Long) {
        synchronized(freeze) { freeze.onThumbnail(luma, nowMs) }
    }

    private sealed interface SessionEnd {
        data object Stalled : SessionEnd
        data object NoVideo : SessionEnd
        data object ConnectionLost : SessionEnd
        data object Expired : SessionEnd
        data object Frozen : SessionEnd
        data object Requested : SessionEnd
    }

    private class SessionResult(val end: SessionEnd, val liveForMs: Long)

    private suspend fun runLoop() {
        var failures = 0
        var reconnects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!networkAvailable.value) {
                publish(if (reconnects == 0) FeedStatus.CONNECTING else FeedStatus.RECONNECTING, reconnects, "Waiting for network")
                networkAvailable.first { it }
            }

            val outcome = attempt { runSession(reconnects) }
            reconnects++
            val result = outcome.getOrNull()
            if (result != null && (result.liveForMs >= timing.stableAfterMs || result.end == SessionEnd.Requested)) {
                failures = 0
            }

            val error = outcome.exceptionOrNull()
            val waitMs: Long = when (error) {
                null -> if (failures == 0) 0 else backoff.delayFor(failures - 1, random)
                is StreamApiException.Unauthorized, is StreamApiException.NotConfigured -> {
                    publish(FeedStatus.AUTH_REQUIRED, reconnects, error.message)
                    reconnectRequests.receive() // wait for the user to sign in again
                    failures = 0
                    continue
                }
                is StreamApiException.RateLimited -> timing.rateLimitedWaitMs
                is StreamApiException.CameraUnavailable -> maxOf(timing.offlineRetryMs, backoff.delayFor(failures, random))
                else -> backoff.delayFor(failures, random)
            }
            if (error != null || result?.end != SessionEnd.Requested) failures++

            if (waitMs > 0) {
                val status = if (error is StreamApiException.CameraUnavailable) FeedStatus.OFFLINE else FeedStatus.RECONNECTING
                publish(status, reconnects, error?.message, nextRetryAtMs = clock() + waitMs)
                waitBeforeRetry(waitMs)
            }
        }
    }

    /** Waits, but retries early on a manual reconnect or when the network comes back. */
    private suspend fun waitBeforeRetry(ms: Long) {
        withTimeoutOrNull(ms) {
            merge(
                reconnectRequests.receiveAsFlow(),
                networkAvailable.drop(1).filter { it }.map { },
            ).first()
        }
    }

    private suspend fun runSession(reconnects: Int): SessionResult {
        lastFrameAtMs = null
        synchronized(freeze) { freeze.reset() }
        publish(if (reconnects == 0) FeedStatus.CONNECTING else FeedStatus.RECONNECTING, reconnects, null)

        val first = withTimeout(timing.connectTimeoutMs) { factory.open(cameraId) }
        activate(first)
        val openedAt = clock()
        var liveSince: Long? = null
        try {
            val end = coroutineScope {
                val ended = CompletableDeferred<SessionEnd>()
                var deadline = openedAt + first.initialExpiresInMs
                var watcher = watchConnection(first, ended)

                // Health: frame age, stalls, frozen picture.
                launch {
                    while (true) {
                        val now = clock()
                        val last = lastFrameAtMs
                        if (last != null && liveSince == null) liveSince = now
                        val unchanged = synchronized(freeze) { freeze.unchangedForMs(now) }
                        when {
                            last == null && now - openedAt >= timing.firstFrameTimeoutMs -> ended.complete(SessionEnd.NoVideo)
                            HealthEvaluator.isStalled(now, last, timing.thresholds) -> ended.complete(SessionEnd.Stalled)
                            unchanged >= timing.thresholds.identicalFrozenMs &&
                                now - lastIdenticalReconnectAt >= timing.identicalReconnectCooldownMs -> {
                                lastIdenticalReconnectAt = now
                                ended.complete(SessionEnd.Frozen)
                            }
                        }
                        val status = HealthEvaluator.pictureStatus(now, last, unchanged, timing.thresholds)
                            ?: if (reconnects == 0) FeedStatus.CONNECTING else FeedStatus.RECONNECTING
                        _health.update {
                            it.copy(
                                status = status,
                                lastFrameAtMs = last ?: it.lastFrameAtMs,
                                liveSinceMs = liveSince,
                                reconnects = reconnects,
                                message = null,
                                nextRetryAtMs = null,
                            )
                        }
                        delay(timing.healthTickMs)
                    }
                }

                // Diagnostics.
                launch {
                    while (true) {
                        delay(timing.statsIntervalMs)
                        val stats = activeSession?.let { s -> attempt { s.stats() }.getOrNull() }
                        if (stats != null) _health.update { it.copy(stats = stats) }
                    }
                }

                // Session lifetime: extend wired cameras, roll battery cameras over.
                launch {
                    var current = first
                    while (true) {
                        if (!extensionsIgnored) {
                            delay((deadline - timing.extendLeadMs - clock()).coerceAtLeast(0))
                            val before = deadline
                            val extended = attempt { current.extend() }
                            val inMs = extended.getOrNull()
                            if (inMs != null) {
                                val newDeadline = clock() + inMs
                                if (newDeadline - before >= timing.minExtensionGainMs) deadline = newDeadline else extensionsIgnored = true
                            } else if (clock() >= deadline - 5_000) {
                                ended.complete(SessionEnd.Expired)
                                return@launch
                            } else {
                                delay(timing.extendRetryMs)
                            }
                        } else {
                            delay((deadline - timing.rolloverLeadMs - clock()).coerceAtLeast(0))
                            val next = attempt { withTimeout(timing.connectTimeoutMs) { factory.open(cameraId) } }.getOrNull()
                            if (next == null) {
                                delay((deadline - clock()).coerceAtLeast(0))
                                ended.complete(SessionEnd.Expired)
                                return@launch
                            }
                            val gotFrame = try {
                                withTimeoutOrNull((deadline - clock()).coerceAtLeast(1_000)) {
                                    while (next.framesReceived == 0L) delay(100)
                                    true
                                } ?: false
                            } catch (e: CancellationException) {
                                withContext(NonCancellable) { next.close() }
                                throw e
                            }
                            if (!gotFrame) {
                                next.close()
                                ended.complete(SessionEnd.Expired)
                                return@launch
                            }
                            val old = current
                            watcher.cancel()
                            activate(next)
                            old.setActive(false)
                            current = next
                            deadline = clock() + next.initialExpiresInMs
                            watcher = watchConnection(next, ended)
                            scope.launch { old.close() }
                        }
                    }
                }

                launch {
                    reconnectRequests.receive()
                    ended.complete(SessionEnd.Requested)
                }

                val reason = ended.await()
                coroutineContext.cancelChildren()
                reason
            }
            return SessionResult(end, liveSince?.let { clock() - it } ?: 0)
        } finally {
            withContext(NonCancellable) {
                activeSession?.let { s ->
                    activeSession = null
                    s.setActive(false)
                    s.close()
                }
            }
        }
    }

    private fun CoroutineScope.watchConnection(
        session: StreamSession,
        ended: CompletableDeferred<SessionEnd>,
    ): Job = launch {
        var grace: Job? = null
        session.connection.collect { state ->
            when (state) {
                SessionConnection.FAILED, SessionConnection.CLOSED -> ended.complete(SessionEnd.ConnectionLost)
                SessionConnection.DISCONNECTED -> if (grace?.isActive != true) {
                    grace = launch {
                        delay(timing.disconnectedGraceMs)
                        ended.complete(SessionEnd.ConnectionLost)
                    }
                }
                SessionConnection.CONNECTING, SessionConnection.CONNECTED -> {
                    grace?.cancel()
                    grace = null
                }
            }
        }
    }

    private fun activate(session: StreamSession) {
        activeSession = session
        session.setAudioGain(audioGain)
        session.setActive(true)
    }

    private fun publish(status: FeedStatus, reconnects: Int, message: String?, nextRetryAtMs: Long? = null) {
        _health.update {
            it.copy(
                status = status,
                reconnects = reconnects,
                message = message,
                nextRetryAtMs = nextRetryAtMs,
                liveSinceMs = if (status.showsLivePicture) it.liveSinceMs else null,
            )
        }
    }

    /** Like runCatching, but never swallows cancellation of the calling coroutine. */
    private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Throwable) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }
}
