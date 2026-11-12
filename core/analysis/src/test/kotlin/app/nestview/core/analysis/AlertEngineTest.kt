package app.nestview.core.analysis

import app.nestview.core.model.AlertSettings
import app.nestview.core.model.AlertType
import app.nestview.core.model.AudioLevel
import app.nestview.core.model.FeedStatus
import app.nestview.core.model.SoundAlertPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private val engine = AlertEngine()
    private val settings = AlertSettings()
    private val alertOn = SoundAlertPrefs(enabled = true, thresholdDb = 14f, sustainSeconds = 3)
    private var now = 0L

    private val quiet = AudioLevel(levelDb = -50f, floorDb = -50f)
    private val loud = AudioLevel(levelDb = -30f, floorDb = -50f) // 20 dB above the room's noise

    private fun cam(
        level: AudioLevel = quiet,
        status: FeedStatus = FeedStatus.LIVE,
        prefs: SoundAlertPrefs = alertOn,
        id: String = "nursery",
    ) = CameraSignal(id, id.replaceFirstChar { it.uppercase() }, level, status, prefs)

    /** Advances time in 200 ms steps (the coordinator's tick) and returns everything that fired. */
    private fun run(ms: Long, device: DeviceSignal = DeviceSignal(), s: AlertSettings = settings, cams: () -> List<CameraSignal>): List<AlertUpdate> {
        val updates = mutableListOf<AlertUpdate>()
        val end = now + ms
        while (now < end) {
            now += 200
            updates += engine.update(now, cams(), device, s)
        }
        return updates
    }

    private fun List<AlertUpdate>.fired() = flatMap { it.fired }

    @Test
    fun soundMustLastBeforeItAlerts() {
        assertTrue(run(2_000) { listOf(cam(loud)) }.fired().isEmpty())
        val fired = run(1_200) { listOf(cam(loud)) }.fired()
        assertEquals(1, fired.size)
        assertEquals(AlertType.SOUND, fired.single().type)
        assertEquals("Sound in Nursery", fired.single().title)
    }

    @Test
    fun shortBreathsDoNotResetTheSustainTimer() {
        run(2_000) { listOf(cam(loud)) }
        run(1_000) { listOf(cam(quiet)) } // a pause shorter than the gap tolerance
        assertEquals(1, run(400) { listOf(cam(loud)) }.fired().size)
    }

    @Test
    fun aLongPauseStartsOver() {
        run(2_000) { listOf(cam(loud)) }
        run(3_000) { listOf(cam(quiet)) }
        assertTrue(run(2_000) { listOf(cam(loud)) }.fired().isEmpty())
    }

    @Test
    fun soundBelowTheLineOrDisabledNeverAlerts() {
        val soft = AudioLevel(levelDb = -40f, floorDb = -50f) // 10 dB above
        assertTrue(run(10_000) { listOf(cam(soft)) }.fired().isEmpty())
        assertTrue(run(10_000) { listOf(cam(loud, prefs = SoundAlertPrefs())) }.fired().isEmpty())
    }

    @Test
    fun alertStaysUntilDismissedThenCoolsDown() {
        run(4_000) { listOf(cam(loud)) }
        val afterQuiet = run(20_000) { listOf(cam(quiet)) }.last().active
        assertEquals(1, afterQuiet.size) // still showing, so a parent who looked away sees it

        engine.dismiss(AlertEngine.soundKey("nursery"), now)
        assertTrue(run(30_000) { listOf(cam(loud)) }.fired().isEmpty()) // cooling down
        assertEquals(1, run(40_000) { listOf(cam(loud)) }.fired().size)
    }

    @Test
    fun snoozeSilencesUntilItEnds() {
        run(4_000) { listOf(cam(loud)) }
        engine.snooze("nursery", now + 600_000)
        assertTrue(run(590_000) { listOf(cam(loud)) }.fired().isEmpty())
        assertEquals(1, run(20_000) { listOf(cam(loud)) }.fired().size)
    }

    @Test
    fun cameraLostAfterThirtySecondsAndClearsWhenBack() {
        assertTrue(run(29_000) { listOf(cam(status = FeedStatus.RECONNECTING)) }.fired().isEmpty())
        val fired = run(2_000) { listOf(cam(status = FeedStatus.RECONNECTING)) }.fired()
        assertEquals(AlertType.CAMERA_LOST, fired.single().type)
        assertTrue(run(400) { listOf(cam(status = FeedStatus.LIVE)) }.last().active.isEmpty())
    }

    @Test
    fun dismissedAlarmWaitsUntilTheProblemComesBack() {
        run(31_000) { listOf(cam(status = FeedStatus.OFFLINE)) }
        engine.dismiss(AlertEngine.lostKey("nursery"), now)
        assertTrue(run(60_000) { listOf(cam(status = FeedStatus.OFFLINE)) }.fired().isEmpty())
        run(1_000) { listOf(cam(status = FeedStatus.LIVE)) }
        assertEquals(1, run(31_000) { listOf(cam(status = FeedStatus.OFFLINE)) }.fired().size)
    }

    @Test
    fun offlineReplacesPerCameraAlarms() {
        val updates = run(40_000, device = DeviceSignal(online = false)) { listOf(cam(status = FeedStatus.RECONNECTING)) }
        assertEquals(listOf(AlertType.OFFLINE), updates.fired().map { it.type })
        assertEquals(listOf(AlertType.OFFLINE), updates.last().active.map { it.type })
    }

    @Test
    fun lowBatteryAlarmHasHysteresisAndClearsOnCharging() {
        val low = DeviceSignal(batteryPercent = 14, charging = false)
        assertEquals(AlertType.LOW_BATTERY, run(400, device = low) { listOf(cam()) }.fired().single().type)
        // Charging a little doesn't flap the alarm off and on.
        assertEquals(1, run(400, device = low.copy(batteryPercent = 17)) { listOf(cam()) }.last().active.size)
        assertTrue(run(400, device = low.copy(charging = true)) { listOf(cam()) }.last().active.isEmpty())
    }

    @Test
    fun turnedOffAlarmsStayQuiet() {
        val off = AlertSettings(cameraLost = false, offline = false, lowBattery = false)
        val device = DeviceSignal(online = false, batteryPercent = 5, charging = false)
        assertTrue(run(60_000, device = device, s = off) { listOf(cam(status = FeedStatus.OFFLINE)) }.fired().isEmpty())
    }

    @Test
    fun cameraLeavingTheViewTakesItsAlertsAlong() {
        run(4_000) { listOf(cam(loud), cam(id = "kitchen")) }
        assertEquals(1, engine.update(now + 200, listOf(cam(loud), cam(id = "kitchen")), DeviceSignal(), settings).active.size)
        assertTrue(engine.update(now + 400, listOf(cam(id = "kitchen")), DeviceSignal(), settings).active.isEmpty())
    }
}
