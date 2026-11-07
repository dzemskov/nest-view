package app.nestview.core.analysis

/**
 * Detects a picture that stopped changing although frames still arrive.
 *
 * Fed with small luma thumbnails (about one per second). Bit-identical thumbnails mean the
 * decoder is repeating the same image: live video, even of a still room at night, always has
 * some sensor noise and periodic key frames.
 */
class FreezeDetector {
    private var lastHash: Long? = null
    private var changedAtMs: Long? = null

    fun onThumbnail(luma: ByteArray, nowMs: Long) {
        val hash = fnv1a(luma)
        if (hash != lastHash || changedAtMs == null) {
            lastHash = hash
            changedAtMs = nowMs
        }
    }

    /** How long the picture has been bit-identical (0 while it changes or before any thumbnail). */
    fun unchangedForMs(nowMs: Long): Long = changedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0

    fun reset() {
        lastHash = null
        changedAtMs = null
    }

    private fun fnv1a(bytes: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV prime
        }
        return hash
    }
}
