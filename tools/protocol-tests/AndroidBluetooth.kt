package android.bluetooth

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothManager(var adapter: BluetoothAdapter?)
open class BluetoothAdapter(private val socket: BluetoothSocket) {
    var isEnabled = true
    // Android 12+ with only CONNECT granted: any discovery operation must fail.
    fun cancelDiscovery(): Boolean = throw SecurityException("SCAN is not granted")
    fun getRemoteDevice(mac: String) = BluetoothDevice(socket, mac)
}
class BluetoothDevice(private val socket: BluetoothSocket, val address: String) {
    companion object { const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE" }
    fun createRfcommSocketToServiceRecord(uuid: UUID) = socket
}
abstract class BluetoothSocket {
    abstract val inputStream: InputStream
    abstract val outputStream: OutputStream
    abstract fun connect()
    abstract fun close()
}
