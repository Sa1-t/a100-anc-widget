package com.alpha.ancwidget

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Minimal GAIA v3 client for the Cambridge Audio Melomania A100.
 *
 * Protocol (verified against a real A100 on 2026-11-06):
 * - Transport: Bluetooth Classic RFCOMM/SPP,
 *   UUID 00001101-0000-1000-8000-00805F9B34FB. The earbuds accept only ONE
 *   RFCOMM connection at a time; if the official Melomania app holds it,
 *   connect() fails.
 * - Frame: [0xFF][version][flags=0x00][payload length 1B][vendor 2B big-endian]
 *   [command 2B big-endian][payload]. No checksum.
 * - 2026-11-06 23:32: direct SWITCH actions 00/01/02 each worked twice without
 *   a handshake. Connect -> SWITCH -> matching ACK -> disconnect. Only if no
 *   matching ACK arrives within 1s, handshake once and retry SWITCH once.
 * - SET (0x000F) was proven ineffective at 23:25 and 23:28, even with a
 *   successful handshake. Never use SET. GET silence does not prove a need
 *   for handshaking; battery refresh simply listens for broadcasts.
 * - Only three ambient-noise states exist on the A100: 00 Normal, 01 ANC,
 *   02 Transparency. SWITCH action 03 is firmware-configured TOGGLE, not a mode.
 *   GET (command 0x0010) gets no reply, so the current mode cannot be read.
 */
object GaiaClient {
    private const val TAG = "GaiaClient"

    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // ---- Byte-exact frames (verified on real hardware, 2026-11-06). Do NOT change. ----
    // Handshake: v1 framing, Qualcomm vendor 0x000A, command 0x0300
    // (GET_API_VERSION), no payload.
    val HANDSHAKE: ByteArray = byteArrayOf(
        0xFF.toByte(), 0x01, 0x00, 0x00,
        0x00, 0x0A, 0x03, 0x00
    )
    // Expected version reply: FF 03 00 04 00 0A 83 00 00 03 03 01 (GAIA v3).
    private val HANDSHAKE_REPLY: ByteArray = byteArrayOf(
        0xFF.toByte(), 0x03, 0x00, 0x04,
        0x00, 0x0A, 0x83.toByte(), 0x00, 0x00, 0x03, 0x03, 0x01
    )
    // TOGGLE: v3 framing, Cambridge vendor 0x0B9E, command 0x000E
    // (SWITCH_AMBIENT_NOISE_MODE), payload 0x03 = action TOGGLE. Its cycle is
    // firmware-configured and unreliable; retained for byte checks, never sent.
    val TOGGLE: ByteArray = byteArrayOf(
        0xFF.toByte(), 0x03, 0x00, 0x01,
        0x0B, 0x9E.toByte(), 0x00, 0x0E, 0x03
    )
    // SWITCH_AMBIENT_NOISE_MODE (0x000E), explicit action = mode (00/01/02).
    // Real A100 confirmed all three; SET 0x000F is ineffective.
    private fun switchFrame(mode: AmbientMode): ByteArray = byteArrayOf(
        0xFF.toByte(), 0x03, 0x00, 0x01,
        0x0B, 0x9E.toByte(), 0x00, 0x0E, mode.value.toByte()
    )

    private const val HANDSHAKE_TIMEOUT_MS = 2000L
    private const val SWITCH_REPLY_TIMEOUT_MS = 1000L
    private const val BATTERY_TIMEOUT_MS = 1000L
    private const val CONNECT_TIMEOUT_MS = 2500L
    private val timeouts = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gaia-connect-timeout").apply { isDaemon = true }
    }

    data class BatteryResult(val received: Boolean, val message: String)
    data class MonoResult(val enabled: Boolean?, val message: String)
    private data class SessionResult(val mode: AmbientMode? = null, val error: String? = null,
                                     val mono: Boolean? = null)

    sealed class Result {
        data class Success(val mode: AmbientMode) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Self-check: the byte arrays must equal the verified hex strings, byte
     * for byte. Throws if anyone edits the constants by mistake, so a wrong
     * frame can never be sent silently.
     */
    fun checkBytes() {
        require(HANDSHAKE.toHex() == "FF010000000A0300") { "HANDSHAKE bytes changed!" }
        require(TOGGLE.toHex() == "FF0300010B9E000E03") { "TOGGLE bytes changed!" }
        require(HANDSHAKE_REPLY.toHex() == "FF030004000A830000030301") { "Handshake reply bytes changed!" }
        require(switchFrame(AmbientMode.NORMAL).toHex() == "FF0300010B9E000E00") { "SWITCH Normal bytes changed!" }
        require(switchFrame(AmbientMode.ANC).toHex() == "FF0300010B9E000E01") { "SWITCH ANC bytes changed!" }
        require(switchFrame(AmbientMode.TRANSPARENCY).toHex() == "FF0300010B9E000E02") { "SWITCH Transparency bytes changed!" }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    /** Direct SWITCH, with a single handshake/retry fallback; battery reads send nothing. */
    fun setMode(
        adapter: BluetoothAdapter,
        mac: String,
        mode: AmbientMode,
        onBattery: (BatteryState) -> Unit = {}
    ): Result {
        val outcome = session(adapter, mac, mode, onBattery)
        return outcome.mode?.let { Result.Success(it) }
            ?: Result.Failure(outcome.error ?: "未收到設定回應，請重試")
    }

    fun readBattery(
        adapter: BluetoothAdapter,
        mac: String,
        onBattery: (BatteryState) -> Unit
    ): BatteryResult {
        var received = false
        val outcome = session(adapter, mac, null, onBattery = {
            received = true
            onBattery(it)
        })
        return BatteryResult(received, if (received) "電量已更新" else
            outcome.error ?: "未收到電量，請重試")
    }

    /** APK-backed modes feature 1, commands 8/9; never confuse this with ANC SET 0x000F. */
    fun mono(adapter: BluetoothAdapter, mac: String, target: Boolean? = null,
             onBattery: (BatteryState) -> Unit = {}): MonoResult {
        val outcome = session(adapter, mac, null, onBattery, monoOperation = true, monoTarget = target)
        return MonoResult(outcome.mono, outcome.error ?: if (target == null) "單聲道狀態已讀取" else "單聲道設定已確認")
    }

    private fun exchangeMono(inp: InputStream, out: OutputStream, target: Boolean?,
                             observe: (ByteArray) -> Unit): SessionResult {
        fun send(frame: ByteArray) { out.write(frame); out.flush() }
        fun query(): Boolean? {
            send(MonoProtocol.query())
            val deadline = SystemClock.uptimeMillis() + SWITCH_REPLY_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val frame = readFrame(inp, deadline) ?: break
                observe(frame)
                MonoProtocol.decode(frame)?.let { return it }
            }
            return null
        }
        var current = query()
        if (current == null) {
            send(HANDSHAKE)
            if (waitForFrame(inp, HANDSHAKE_REPLY, HANDSHAKE_TIMEOUT_MS, observe) != null) current = query()
        }
        if (current == null) return SessionResult(error = "未讀到單聲道狀態；耳機未回應或韌體不支援")
        if (target == null || target == current) return SessionResult(mono = current)
        send(MonoProtocol.set(target))
        // Read back actual state. A successful write or an ANC ACK never confirms mono.
        val actual = query()
        return when {
            actual == null -> SessionResult(error = "單聲道結果未確認，請重新讀取")
            actual != target -> SessionResult(mono = actual, error = "耳機回報的單聲道狀態未改變")
            else -> SessionResult(mono = actual)
        }
    }

    /** Off-main-thread, bounded connection; unsolicited battery frames are demultiplexed from ACKs. */
    private fun session(
        adapter: BluetoothAdapter,
        mac: String,
        mode: AmbientMode?,
        onBattery: (BatteryState) -> Unit,
        monoOperation: Boolean = false, monoTarget: Boolean? = null
    ): SessionResult {
        checkBytes()
        var socket: BluetoothSocket? = null
        try {
            val device = adapter.getRemoteDevice(mac)
            val connection = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = connection
            // We never discover devices. cancelDiscovery() would require SCAN on
            // Android 12+, while this bonded-device SPP flow only needs CONNECT.
            val timeout = timeouts.schedule({
                try { connection.close() } catch (_: IOException) { }
            }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try {
                connection.connect()
            } catch (e: IOException) {
                Log.w(TAG, "RFCOMM connect failed", e)
                return SessionResult(error = "連線失敗：請先關掉 Melomania App")
            } finally {
                timeout.cancel(false)
            }
            val batteryDeadline = SystemClock.uptimeMillis() + BATTERY_TIMEOUT_MS
            val out = connection.outputStream
            val inp = connection.inputStream
            var batteryReceived = false
            fun observe(frame: ByteArray) {
                if (!batteryReceived) {
                    BatteryState.parse(frame)?.let {
                        batteryReceived = true
                        onBattery(it)
                    }
                }
            }
            if (monoOperation) return exchangeMono(inp, out, monoTarget, ::observe)
            var confirmed: AmbientMode? = null
            if (mode != null) {
                val command = switchFrame(mode)
                // Only the exact 0x010E reply with the requested action confirms a change.
                val expectedReply = command.copyOf().apply { this[6] = 0x01 }
                fun sendSwitch(): Boolean {
                    out.write(command)
                    out.flush()
                    return waitForFrame(inp, expectedReply, SWITCH_REPLY_TIMEOUT_MS, ::observe) != null
                }
                // Handshake is NOT a prerequisite. Retry the same explicit action, never TOGGLE.
                if (!sendSwitch()) {
                    out.write(HANDSHAKE)
                    out.flush()
                    if (waitForFrame(inp, HANDSHAKE_REPLY, HANDSHAKE_TIMEOUT_MS, ::observe) == null) {
                        return SessionResult(error = "重試握手未回應，請重試")
                    }
                    if (!sendSwitch()) return SessionResult(error = "未收到設定回應，請重試")
                }
                confirmed = mode
            }
            // At most the remainder of the first second after connecting. A missing battery
            // broadcast never turns an acknowledged SWITCH into a failure.
            while (!batteryReceived && SystemClock.uptimeMillis() < batteryDeadline) {
                val frame = readFrame(inp, batteryDeadline) ?: break
                observe(frame)
            }
            return SessionResult(mode = confirmed)
        } catch (_: IllegalArgumentException) {
            return SessionResult(error = "未設定耳機")
        } catch (_: SecurityException) {
            return SessionResult(error = "缺少藍牙權限")
        } catch (e: IOException) {
            Log.w(TAG, "IO error", e)
            return SessionResult(error = "連線失敗，請重試")
        } finally {
            try { socket?.close() } catch (_: IOException) { }
        }
    }

    /** Observe every frame while waiting, so broadcasts never hide a version or mode ACK. */
    private fun waitForFrame(
        inp: InputStream,
        expected: ByteArray,
        timeoutMs: Long,
        observe: (ByteArray) -> Unit
    ): ByteArray? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val frame = readFrame(inp, deadline) ?: return null
            observe(frame)
            if (frame.contentEquals(expected)) {
                return frame
            }
        }
        return null
    }

    /** Read one GAIA frame, resynchronising on 0xFF if needed. Null on timeout / EOF / error. */
    private fun readFrame(inp: InputStream, deadline: Long): ByteArray? {
        var b: Int
        do {
            b = readByteBefore(inp, deadline) ?: return null
        } while (b != 0xFF)
        val header = ByteArray(4)
        header[0] = 0xFF.toByte()
        var read = 1
        while (read < 4) {
            val n = readInto(inp, header, read, 4 - read, deadline) ?: return null
            read += n
        }
        val payloadLen = header[3].toInt() and 0xFF
        val total = 8 + payloadLen
        val frame = ByteArray(total)
        System.arraycopy(header, 0, frame, 0, 4)
        read = 4
        while (read < total) {
            val n = readInto(inp, frame, read, total - read, deadline) ?: return null
            read += n
        }
        return frame
    }

    private fun readByteBefore(inp: InputStream, deadline: Long): Int? {
        val one = ByteArray(1)
        val n = readInto(inp, one, 0, 1, deadline) ?: return null
        return if (n == 1) one[0].toInt() and 0xFF else null
    }

    /**
     * Read at least 1 byte (up to [len]) before [deadline].
     * Null on timeout / EOF / error. BluetoothSocket has no read timeout, so
     * availability is polled instead of blocking.
     */
    private fun readInto(
        inp: InputStream,
        buf: ByteArray,
        off: Int,
        len: Int,
        deadline: Long
    ): Int? {
        while (true) {
            if (SystemClock.uptimeMillis() >= deadline) return null
            val avail = try {
                inp.available()
            } catch (_: IOException) {
                return null
            }
            if (avail > 0) {
                val n = try {
                    inp.read(buf, off, minOf(len, avail))
                } catch (_: IOException) {
                    return null
                }
                if (n < 0) return null
                return n
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return null
            }
        }
    }
}
