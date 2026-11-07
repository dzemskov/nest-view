package app.nestview.core.streaming.webrtc

import android.content.Context
import android.media.AudioAttributes
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide WebRTC objects: one GL context shared by every renderer and decoder, and one
 * factory whose audio module mixes all remote streams into a single media-volume output.
 */
@Singleton
class WebRtcEnvironment @Inject constructor(@ApplicationContext private val context: Context) {

    val eglBase: EglBase by lazy {
        initializeOnce()
        EglBase.create()
    }

    val factory: PeerConnectionFactory by lazy { createFactory() }

    private fun createFactory(): PeerConnectionFactory {
        initializeOnce()
        // Media usage (not voice call): loudspeaker, media volume keys, no microphone needed.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(attributes)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()
        val egl = eglBase.eglBaseContext
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .createPeerConnectionFactory()
    }

    private fun initializeOnce() {
        synchronized(lock) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }

    private companion object {
        val lock = Any()
        var initialized = false
    }
}
