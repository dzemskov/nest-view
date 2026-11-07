package app.nestview.core.model

/**
 * Sound level of one stream in dBFS. [floorDb] is the learned background level (e.g. a
 * white-noise machine), so activity is judged relative to the room's normal sound.
 */
data class AudioLevel(
    val levelDb: Float = SILENCE_DB,
    val peakDb: Float = SILENCE_DB,
    val floorDb: Float = SILENCE_DB,
) {
    /** 0..1 for meters, mapping -60..0 dBFS. */
    val meter: Float get() = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)

    /** Background level on the same 0..1 scale as [meter]. */
    val floorMeter: Float get() = ((floorDb + 60f) / 60f).coerceIn(0f, 1f)

    val aboveFloorDb: Float get() = (levelDb - floorDb).coerceAtLeast(0f)

    val activity: SoundActivity
        get() = when {
            levelDb < QUIET_BELOW_DB -> SoundActivity.QUIET
            aboveFloorDb >= 22f -> SoundActivity.LOUD
            aboveFloorDb >= 14f -> SoundActivity.NOTICEABLE
            aboveFloorDb >= 8f -> SoundActivity.SOFT
            else -> SoundActivity.QUIET
        }

    companion object {
        const val SILENCE_DB = -90f
        const val QUIET_BELOW_DB = -55f
        val Silent = AudioLevel()
    }
}

enum class SoundActivity { QUIET, SOFT, NOTICEABLE, LOUD }
