package com.alpha.ancwidget

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.ArrayDeque

private fun bytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private class MonoDevice(var enabled: Boolean = false, val supported: Boolean = true,
                         val applySet: Boolean = true, val loseReadback: Boolean = false,
                         val offline: Boolean = false, val handshakeRequired: Boolean = false) : BluetoothSocket() {
    val writes = mutableListOf<String>()
    val incoming = ArrayDeque<Int>()
    var closed = false
    var set = false
    var handshaken = false
    fun enqueue(hex: String) { bytes(hex).forEach { incoming.add(it.toInt() and 255) } }
    override fun connect() { if (offline) throw IOException("offline") }
    override fun close() { closed = true }
    override val inputStream = object : InputStream() {
        override fun available() = incoming.size
        override fun read() = incoming.poll() ?: -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(length, 2, incoming.size)
            repeat(count) { buffer[offset + it] = read().toByte() }
            return count
        }
    }
    override val outputStream = object : OutputStream() {
        override fun write(value: Int) = error("Frame writes only")
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val hex = buffer.copyOfRange(offset, offset + length).joinToString("") { "%02X".format(it) }
            writes.add(hex)
            when (hex) {
                "FF0300000B9E0209" -> {
                    // An ANC ACK and a battery frame must not count as a mono reply.
                    enqueue("FF0300010B9E010E01")
                    enqueue("FF0300090B9E01030100000062644E0000")
                    if (supported && (!set || !loseReadback) && (!handshakeRequired || handshaken)) {
                        enqueue("FF0300010B9E0309" + if (enabled) "01" else "00")
                    }
                }
                "FF0300010B9E020801", "FF0300010B9E020800" -> {
                    set = true
                    if (applySet) enabled = hex.endsWith("01")
                    enqueue("FF0300000B9E0308") // A SET response alone never confirms the value.
                }
                "FF010000000A0300" -> { handshaken = true; enqueue("FF030004000A830000030301") }
                else -> error("Unexpected mono command: $hex")
            }
        }
    }
}

fun main() {
    check(MonoProtocol.query().contentEquals(bytes("FF0300000B9E0209")))
    check(MonoProtocol.set(false).contentEquals(bytes("FF0300010B9E020800")))
    check(MonoProtocol.set(true).contentEquals(bytes("FF0300010B9E020801")))
    check(MonoProtocol.decode(bytes("FF0300010B9E030900")) == false)
    check(MonoProtocol.decode(bytes("FF0300010B9E030901")) == true)
    for (invalid in listOf("FF0300010B9E010E01", "FF0300010B9E038901", "FF030001000A030901",
        "FF0300010B9E030902", "FF0300020B9E03090100", "FF0300010B9E0309", "FF0300010B9E030801")) {
        check(MonoProtocol.decode(bytes(invalid)) == null)
    }
    val context = Context()
    context.prefs.edit().putString(MainActivity.KEY_MAC, "00:11:22:33:44:55")
        .putInt("last_set_mode", 2).apply()
    fun attach(device: MonoDevice) { context.service = BluetoothManager(BluetoothAdapter(device)) }
    for (target in listOf(false, true)) {
        val device = MonoDevice(enabled = !target)
        attach(device)
        check(AncModeController.mono(context, target).enabled == target)
        check(device.writes == listOf("FF0300000B9E0209", "FF0300010B9E0208" + if (target) "01" else "00", "FF0300000B9E0209"))
        check(device.closed && AncModeController.lastConfirmedMode(context) == AmbientMode.TRANSPARENCY)
        check(BatteryStore.read(context).left == 98)
    }
    val unchanged = MonoDevice(enabled = true)
    attach(unchanged)
    check(AncModeController.mono(context, true).enabled == true)
    check(unchanged.writes == listOf("FF0300000B9E0209") && unchanged.closed)
    val readOnly = MonoDevice()
    attach(readOnly)
    check(AncModeController.mono(context).enabled == false)
    check(readOnly.writes == listOf("FF0300000B9E0209") && readOnly.closed)
    val rejected = MonoDevice(applySet = false)
    attach(rejected)
    val rejectedResult = AncModeController.mono(context, true)
    check(rejectedResult.enabled == false && rejectedResult.message.contains("未改變"))
    val lost = MonoDevice(loseReadback = true)
    attach(lost)
    check(AncModeController.mono(context, true).enabled == null && lost.closed)
    val unsupported = MonoDevice(supported = false)
    attach(unsupported)
    check(AncModeController.mono(context, true).enabled == null && unsupported.closed)
    check(!unsupported.set && unsupported.writes == listOf("FF0300000B9E0209", "FF010000000A0300", "FF0300000B9E0209"))
    val needsHandshake = MonoDevice(handshakeRequired = true)
    attach(needsHandshake)
    check(AncModeController.mono(context, true).enabled == true && needsHandshake.closed)
    check(needsHandshake.writes == listOf("FF0300000B9E0209", "FF010000000A0300", "FF0300000B9E0209", "FF0300010B9E020801", "FF0300000B9E0209"))
    val offline = MonoDevice(offline = true)
    attach(offline)
    check(AncModeController.mono(context, true).enabled == null && offline.closed && offline.writes.isEmpty())
    println("PASS mono: exact APK-derived bytes, strict readback validation, on/off/query/idempotence, mismatched or missing readback, unsupported no SET, one handshake fallback, offline close; battery and ANC state preserved")
}
