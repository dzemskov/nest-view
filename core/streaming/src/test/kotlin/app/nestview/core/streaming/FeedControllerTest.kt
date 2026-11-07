package app.nestview.core.streaming

import app.nestview.core.model.FeedStatus
import app.nestview.core.model.StreamApiException
import app.nestview.core.model.StreamStats
import app.nestview.core.streaming.webrtc.SdpOffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FeedControllerTest {

    private class FakeSession(
        override val id: String,
        override val initialExpiresInMs: Long,
        private val extendBy: () -> Long,
    ) : StreamSession {
        override val connection = MutableStateFlow(SessionConnection.CONNECTED)
        var frames = 0L
        override val framesReceived: Long get() = frames
        var live = false
        var closed = false
        var streaming = true
        var extends = 0

        override suspend fun extend(): Long {
            extends++
            return extendBy()
        }
        override fun setActive(active: Boolean) {
            live = active
        }
        override fun setAudioGain(gain: Float) = Unit
        override suspend fun stats(): StreamStats? = null
        override suspend fun close() {
            closed = true
            live = false
        }
    }

    private class Harness(val scope: TestScope, extendBy: () -> Long = { 300_000L }) {
        val sessions = mutableListOf<FakeSession>()
        var failNext: Exception? = null
        val network = MutableStateFlow(true)
        private val extend = extendBy

        val controller = FeedController(
            cameraId = "cam",
            factory = StreamSessionFactory {
                failNext?.let { failNext = null; throw it }
                FakeSession("s${sessions.size}", 300_000L, extend).also { sessions += it }
            },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
            networkAvailable = network as StateFlow<Boolean>,
            random = Random(0),
        )

        /** Emits frames (10 per second) for every streaming session, like a real camera. */
        fun startCameras() {
            scope.backgroundScope.launch {
                while (isActive) {
                    delay(100)
                    for (s in sessions.toList()) {
                        if (s.closed || !s.streaming) continue
                        s.frames++
                        if (s.live) {
                            controller.onFrame(scope.testScheduler.currentTime)
                            controller.onThumbnail(byteArrayOf((s.frames % 7).toByte()), scope.testScheduler.currentTime)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun goesLiveAndExtendsBeforeExpiry() = runTest {
        val h = Harness(this)
        h.startCameras()
        h.controller.start()
        testScheduler.advanceTimeBy(2_000)
        assertEquals(FeedStatus.LIVE, h.controller.health.value.status)

        testScheduler.advanceTimeBy(4 * 60_000L) // past expiry - 60 s
        assertEquals(1, h.sessions.single().extends)
        assertEquals(FeedStatus.LIVE, h.controller.health.value.status)
    }

    @Test
    fun stalledVideoReconnects() = runTest {
        val h = Harness(this)
        h.startCameras()
        h.controller.start()
        testScheduler.advanceTimeBy(5_000)
        h.sessions[0].streaming = false

        testScheduler.advanceTimeBy(3_000)
        assertEquals(FeedStatus.DELAYED, h.controller.health.value.status)
        testScheduler.advanceTimeBy(3_000)
        assertEquals(FeedStatus.FROZEN, h.controller.health.value.status)

        testScheduler.advanceTimeBy(10_000)
        assertTrue(h.sessions[0].closed)
        assertEquals(2, h.sessions.size)
        testScheduler.advanceTimeBy(5_000)
        assertEquals(FeedStatus.LIVE, h.controller.health.value.status)
        assertEquals(1, h.controller.health.value.reconnects)
    }

    @Test
    fun batteryCameraRollsOverWithoutGap() = runTest {
        // Extensions are ignored: lifetime stays at the old deadline.
        lateinit var h: Harness
        h = Harness(this) { 300_000L - testScheduler.currentTime }
        h.startCameras()
        h.controller.start()
        val statuses = mutableSetOf<FeedStatus>()
        backgroundScope.launch {
            delay(1_000)
            while (isActive) {
                statuses += h.controller.health.value.status
                delay(250)
            }
        }
        testScheduler.advanceTimeBy(5 * 60_000L)
        assertEquals(2, h.sessions.size)
        assertTrue(h.sessions[0].closed)
        assertTrue(h.sessions[1].live)
        assertEquals(setOf(FeedStatus.LIVE), statuses)
    }

    @Test
    fun signInProblemWaitsForUser() = runTest {
        val h = Harness(this)
        h.failNext = StreamApiException.Unauthorized("expired")
        h.startCameras()
        h.controller.start()
        testScheduler.advanceTimeBy(10 * 60_000L)
        assertEquals(FeedStatus.AUTH_REQUIRED, h.controller.health.value.status)
        assertTrue(h.sessions.isEmpty())

        h.controller.requestReconnect()
        testScheduler.advanceTimeBy(2_000)
        assertEquals(1, h.sessions.size)
        assertEquals(FeedStatus.LIVE, h.controller.health.value.status)
    }

    @Test
    fun rateLimitWaitsAMinute() = runTest {
        val h = Harness(this)
        h.failNext = StreamApiException.RateLimited("busy")
        h.startCameras()
        h.controller.start()
        testScheduler.advanceTimeBy(50_000)
        assertTrue(h.sessions.isEmpty())
        assertEquals(FeedStatus.RECONNECTING, h.controller.health.value.status)
        testScheduler.advanceTimeBy(15_000)
        assertEquals(1, h.sessions.size)
    }

    @Test
    fun waitsForNetworkThenConnects() = runTest {
        val h = Harness(this)
        h.network.value = false
        h.startCameras()
        h.controller.start()
        testScheduler.advanceTimeBy(30_000)
        assertTrue(h.sessions.isEmpty())
        h.network.value = true
        testScheduler.advanceTimeBy(1_000)
        assertEquals(1, h.sessions.size)
    }

    @Test
    fun stopClosesSession() = runTest {
        val h = Harness(this)
        h.startCameras()
        h.controller.start()
        testScheduler.advanceTimeBy(2_000)
        h.controller.stop()
        testScheduler.advanceTimeBy(1_000)
        assertTrue(h.sessions.single().closed)
        assertFalse(h.sessions.single().live)
        assertEquals(FeedStatus.STOPPED, h.controller.health.value.status)
    }
}

class SdpOfferTest {
    private val good = listOf(
        "v=0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "a=recvonly",
        "a=rtpmap:111 opus/48000/2",
        "m=video 9 UDP/TLS/RTP/SAVPF 102",
        "a=recvonly",
        "a=rtpmap:102 H264/90000",
        "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
    ).joinToString("\r\n")

    @Test
    fun acceptsGoogleShapedOffer() {
        assertEquals(emptyList<String>(), SdpOffer.validate(SdpOffer.normalize(good)))
    }

    @Test
    fun flagsMissingNewlineAndWrongOrder() {
        assertTrue(SdpOffer.validate(good).any { it.contains("newline") })
        val reordered = good.replace("m=audio", "m=tmp").replace("m=video", "m=audio").replace("m=tmp", "m=video")
        assertTrue(SdpOffer.validate(SdpOffer.normalize(reordered)).any { it.contains("order") })
    }
}
