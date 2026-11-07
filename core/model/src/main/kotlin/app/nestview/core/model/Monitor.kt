package app.nestview.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How tiles are arranged. Each view stores one spec for portrait and one for landscape. */
@Serializable
sealed interface LayoutSpec {
    /** Pick the arrangement that shows the most video for the screen shape. */
    @Serializable
    @SerialName("auto")
    data object Auto : LayoutSpec

    @Serializable
    @SerialName("grid")
    data class Grid(val columns: Int) : LayoutSpec

    /** One large tile plus a strip of small ones. */
    @Serializable
    @SerialName("focus")
    data class Focus(val primaryId: String? = null) : LayoutSpec

    /** All tiles side by side. */
    @Serializable
    @SerialName("row")
    data object Row : LayoutSpec

    /** All tiles stacked. */
    @Serializable
    @SerialName("column")
    data object Column : LayoutSpec
}

/**
 * The audio mix of a view. Muting never stops audio data: muted cameras keep their level
 * meters and sound indication, only playback is silenced.
 */
@Serializable
data class AudioMix(
    val muted: Set<String> = emptySet(),
    val soloId: String? = null,
    val allMuted: Boolean = false,
) {
    fun isMuted(cameraId: String): Boolean =
        allMuted || cameraId in muted || (soloId != null && soloId != cameraId)

    fun gainFor(cameraId: String, volume: Float): Float =
        if (isMuted(cameraId)) 0f else volume.coerceIn(0f, MAX_VOLUME)

    /** Toggles one camera. Unmuting a camera that was silenced by solo or "mute all" keeps the others silent. */
    fun toggleMute(cameraId: String, allIds: Collection<String>): AudioMix {
        if (!isMuted(cameraId)) return copy(muted = muted + cameraId)
        return when {
            allMuted -> AudioMix(muted = allIds.toSet() - cameraId)
            soloId != null -> AudioMix(muted = allIds.toSet() - soloId - cameraId)
            else -> copy(muted = muted - cameraId)
        }
    }

    fun toggleSolo(cameraId: String): AudioMix =
        copy(soloId = if (soloId == cameraId) null else cameraId, allMuted = false)

    fun toggleAllMuted(): AudioMix = copy(allMuted = !allMuted)

    fun retainOnly(ids: Collection<String>): AudioMix = copy(
        muted = muted.filterTo(mutableSetOf()) { it in ids },
        soloId = soloId?.takeIf { it in ids },
    )

    companion object {
        const val MAX_VOLUME = 3f
    }
}

/** A named set of cameras with its layouts and audio mix. */
@Serializable
data class MonitorView(
    val id: String,
    val name: String,
    val cameraIds: List<String>,
    val portrait: LayoutSpec = LayoutSpec.Auto,
    val landscape: LayoutSpec = LayoutSpec.Auto,
    val audio: AudioMix = AudioMix(),
) {
    fun layoutFor(landscape: Boolean): LayoutSpec = if (landscape) this.landscape else portrait

    companion object {
        const val ALL_CAMERAS_ID = "all"
    }
}
