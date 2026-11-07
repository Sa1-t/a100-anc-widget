package android.bluetooth
class BluetoothManager(var adapter: BluetoothAdapter?)
class BluetoothAdapter {
    var isEnabled = true
    var bondedDevices = setOf(BluetoothDevice("00:11:22:33:44:55", "Melomania A100"))
}
class BluetoothDevice(val address: String, val name: String?)
