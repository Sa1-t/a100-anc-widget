package com.alpha.ancwidget

/** Connect 2.6.0: Cambridge modesPlugin=1; ModesIds SET_MONO_MODE=8, GET_MONO_MODE=9.
 * See docs/audio-features.md. Hardware readback is mandatory; no optimistic state.
 */
internal object MonoProtocol {
    fun query(): ByteArray = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x00, 0x0B, 0x9E.toByte(), 0x02, 0x09)
    fun set(enabled: Boolean): ByteArray = byteArrayOf(
        0xFF.toByte(), 0x03, 0x00, 0x01, 0x0B, 0x9E.toByte(), 0x02, 0x08, if (enabled) 1 else 0)
    fun decode(frame: ByteArray): Boolean? {
        val header = byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x01, 0x0B, 0x9E.toByte(), 0x03, 0x09)
        if (frame.size != 9 || !frame.copyOfRange(0, 8).contentEquals(header)) return null
        return when (frame[8].toInt()) { 0 -> false; 1 -> true; else -> null }
    }
}
