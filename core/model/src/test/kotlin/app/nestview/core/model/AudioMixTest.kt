package app.nestview.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixTest {
    private val ids = listOf("a", "b", "c")

    @Test
    fun soloSilencesOthers() {
        val mix = AudioMix().toggleSolo("b")
        assertTrue(mix.isMuted("a"))
        assertFalse(mix.isMuted("b"))
        assertEquals(0f, mix.gainFor("c", 1f))
        assertEquals(1.5f, mix.gainFor("b", 1.5f))
    }

    @Test
    fun unmutingDuringSoloKeepsRestSilent() {
        val mix = AudioMix().toggleSolo("b").toggleMute("a", ids)
        assertFalse(mix.isMuted("a"))
        assertFalse(mix.isMuted("b"))
        assertTrue(mix.isMuted("c"))
    }

    @Test
    fun unmutingDuringMuteAllUnmutesOnlyThatCamera() {
        val mix = AudioMix().toggleAllMuted().toggleMute("c", ids)
        assertFalse(mix.isMuted("c"))
        assertTrue(mix.isMuted("a"))
        assertTrue(mix.isMuted("b"))
    }

    @Test
    fun volumeIsCapped() {
        assertEquals(AudioMix.MAX_VOLUME, AudioMix().gainFor("a", 10f))
    }
}
