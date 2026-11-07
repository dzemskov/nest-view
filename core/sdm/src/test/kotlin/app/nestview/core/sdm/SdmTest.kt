package app.nestview.core.sdm

import app.nestview.core.model.StreamApiException
import app.nestview.core.model.VideoSize
import app.nestview.core.sdm.api.DeviceMapping
import app.nestview.core.sdm.api.DevicesResponse
import app.nestview.core.sdm.api.SdmErrors
import app.nestview.core.sdm.api.SdmJson
import app.nestview.core.sdm.api.toMapping
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMappingTest {
    private val json = """
        {
          "devices": [
            {
              "name": "enterprises/p1/devices/cam1",
              "type": "sdm.devices.types.CAMERA",
              "traits": {
                "sdm.devices.traits.Info": { "customName": "" },
                "sdm.devices.traits.CameraLiveStream": {
                  "maxVideoResolution": { "width": 640, "height": 480 },
                  "videoCodecs": ["H264"],
                  "audioCodecs": ["OPUS"],
                  "supportedProtocols": ["WEB_RTC"]
                }
              },
              "parentRelations": [ { "parent": "enterprises/p1/structures/s/rooms/r", "displayName": "Nursery" } ]
            },
            {
              "name": "enterprises/p1/devices/old",
              "type": "sdm.devices.types.CAMERA",
              "traits": { "sdm.devices.traits.CameraLiveStream": { "supportedProtocols": ["RTSP"] } }
            },
            {
              "name": "enterprises/p1/devices/thermo",
              "type": "sdm.devices.types.THERMOSTAT",
              "traits": { "sdm.devices.traits.Temperature": { "ambientTemperatureCelsius": 21.5 } }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun mapsWebRtcCamerasAndSkipsOthers() {
        val devices = SdmJson.decodeFromString<DevicesResponse>(json).devices
        val mapped = devices.map { it.toMapping() }
        val camera = (mapped[0] as DeviceMapping.Camera).info
        assertEquals("enterprises/p1/devices/cam1", camera.id)
        assertEquals("Nursery", camera.name) // blank custom name falls back to the room
        assertEquals(VideoSize(640, 480), camera.maxResolution)
        assertEquals(DeviceMapping.UnsupportedCamera, mapped[1])
        assertEquals(DeviceMapping.NotACamera, mapped[2])
    }
}

class SdmErrorsTest {
    @Test
    fun mapsGoogleStatuses() {
        val busy = """{"error":{"code":429,"message":"Rate limited.","status":"RESOURCE_EXHAUSTED"}}"""
        assertTrue(SdmErrors.from(429, busy) is StreamApiException.RateLimited)
        val offline = """{"error":{"code":400,"message":"The camera is not available for streaming.","status":"FAILED_PRECONDITION"}}"""
        assertTrue(SdmErrors.from(400, offline) is StreamApiException.CameraUnavailable)
        assertTrue(SdmErrors.from(401, "") is StreamApiException.Unauthorized)
        assertTrue(SdmErrors.from(500, "not json") is StreamApiException.Transient)
    }

    @Test
    fun expiryFallsBackWhenClockIsOff() {
        val now = java.time.Instant.parse("2027-01-15T08:00:00Z").toEpochMilli()
        assertEquals(240_000L, SdmCameraStreamApi.expiresIn("2027-01-15T08:04:00Z", now))
        assertEquals(300_000L, SdmCameraStreamApi.expiresIn("2001-01-01T00:00:00Z", now))
        assertEquals(300_000L, SdmCameraStreamApi.expiresIn(null, now))
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommandRateLimiterTest {
    @Test
    fun generateLeavesRoomForExtensions() = runTest {
        val limiter = CommandRateLimiter { testScheduler.currentTime }
        // Six new streams use the non-reserved slots immediately (2 per device).
        for (i in 0 until 6) assertTrue(limiter.acquire("cam${i % 3}", CommandPriority.GENERATE))
        assertEquals(0L, testScheduler.currentTime)
        // An extension still gets a reserved slot at once.
        assertTrue(limiter.acquire("cam0", CommandPriority.EXTEND))
        assertEquals(0L, testScheduler.currentTime)
        // A stop is skipped rather than queued when only the reserve is left.
        assertFalse(limiter.acquire("cam4", CommandPriority.STOP))
        // Another stream waits for the window to roll.
        val pending = async { limiter.acquire("cam5", CommandPriority.GENERATE) }
        testScheduler.advanceTimeBy(59_000)
        assertFalse(pending.isCompleted)
        testScheduler.advanceTimeBy(2_000)
        assertTrue(pending.await())
    }

    @Test
    fun perDeviceLimitApplies() = runTest {
        val limiter = CommandRateLimiter { testScheduler.currentTime }
        repeat(4) { assertTrue(limiter.acquire("cam", CommandPriority.EXTEND)) }
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(limiter.acquire("cam", CommandPriority.EXTEND))
        assertTrue(testScheduler.currentTime >= 60_000)
    }
}
