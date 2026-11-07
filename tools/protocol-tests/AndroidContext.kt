package android.content

open class Context {
    companion object { const val MODE_PRIVATE = 0; const val BLUETOOTH_SERVICE = "bluetooth"; const val RECEIVER_EXPORTED = 2 }
    val prefs = SharedPreferences()
    var service: Any? = null
    var connectGranted = true
    var startedServices = 0
    var stoppedServices = 0
    var rejectStart = false
    var registeredReceiver: BroadcastReceiver? = null
    var receiverFlags: Int? = null
    var registeredAction: String? = null
    fun getSharedPreferences(name: String, mode: Int) = prefs
    fun getSystemService(name: String) = service
    @Suppress("UNCHECKED_CAST")
    fun <T> getSystemService(cls: Class<T>): T = android.app.NotificationManager() as T
    fun checkSelfPermission(permission: String) = if (connectGranted) 0 else -1
    fun startForegroundService(intent: Intent) {
        startedServices++
        if (rejectStart) throw IllegalStateException("Background start denied")
    }
    fun stopService(intent: Intent): Boolean { stoppedServices++; return true }
    fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        registeredReceiver = receiver; registeredAction = filter.action; receiverFlags = null
    }
    fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter, flags: Int) {
        registerReceiver(receiver, filter); receiverFlags = flags
    }
    fun unregisterReceiver(receiver: BroadcastReceiver) {
        check(registeredReceiver === receiver); registeredReceiver = null
    }
}

class SharedPreferences {
    val values = mutableMapOf<String, Any>()
    fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    fun getString(key: String, default: String?) = values[key] as? String ?: default
    fun edit() = Editor()
    inner class Editor {
        private val changes = mutableMapOf<String, Any?>()
        fun putInt(key: String, value: Int) = apply { changes[key] = value }
        fun putLong(key: String, value: Long) = apply { changes[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun apply() { changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value } }
    }
}

class ComponentName(context: Context, cls: Class<*>)

abstract class BroadcastReceiver { abstract fun onReceive(context: Context, intent: Intent) }
class IntentFilter(val action: String)
class Intent() {
    constructor(context: Context, cls: Class<*>) : this()
    var action: String? = null
    private val extras = mutableMapOf<String, Any>()
    fun putExtra(key: String, value: Any) = apply { extras[key] = value }
    fun getIntExtra(key: String, default: Int) = extras[key] as? Int ?: default
    @Suppress("UNCHECKED_CAST")
    fun <T> getParcelableExtra(key: String): T? = extras[key] as? T
    fun <T> getParcelableExtra(key: String, cls: Class<T>): T? = extras[key]?.let { cls.cast(it) }
    companion object {
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    }
}
