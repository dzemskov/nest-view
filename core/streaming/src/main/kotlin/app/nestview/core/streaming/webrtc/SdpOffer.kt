package app.nestview.core.streaming.webrtc

/** Google's rules for the SDP offer sent to `GenerateWebRtcStream`. */
object SdpOffer {

    /** The offer must end with a newline. */
    fun normalize(sdp: String): String = if (sdp.endsWith("\n")) sdp else "$sdp\r\n"

    /** Returns the rule violations; empty when the offer is acceptable. */
    fun validate(sdp: String): List<String> {
        val problems = mutableListOf<String>()
        val sections = sections(sdp)
        val kinds = sections.map { it.kind }
        if (kinds != listOf("audio", "video", "application")) {
            problems += "m-lines must be audio, video, application in that order (got ${kinds.joinToString()})"
        }
        sections.firstOrNull { it.kind == "audio" }?.let { audio ->
            if (audio.lines.none { it == "a=recvonly" }) problems += "audio must be a=recvonly"
            if (audio.lines.none { it.startsWith("a=rtpmap:") && it.contains("opus/48000", ignoreCase = true) }) {
                problems += "audio must offer Opus"
            }
        }
        sections.firstOrNull { it.kind == "video" }?.let { video ->
            if (video.lines.none { it.startsWith("a=rtpmap:") && it.contains("H264/90000", ignoreCase = true) }) {
                problems += "video must offer H.264"
            }
        }
        if (!sdp.endsWith("\n")) problems += "offer must end with a newline"
        return problems
    }

    private class Section(val kind: String, val lines: List<String>)

    private fun sections(sdp: String): List<Section> {
        val result = mutableListOf<Section>()
        var kind: String? = null
        var lines = mutableListOf<String>()
        for (raw in sdp.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("m=")) {
                kind?.let { result += Section(it, lines) }
                kind = line.removePrefix("m=").substringBefore(' ')
                lines = mutableListOf()
            } else if (kind != null && line.isNotEmpty()) {
                lines += line
            }
        }
        kind?.let { result += Section(it, lines) }
        return result
    }
}
