package android.content
open class Context {
    companion object { const val MODE_PRIVATE = 0; const val BLUETOOTH_SERVICE = "bluetooth" }
    val prefs = SharedPreferences()
    var service: Any? = null
    var connectGranted = true
    fun getSharedPreferences(name: String, mode: Int) = prefs
    fun getSystemService(name: String) = service
    fun checkSelfPermission(permission: String): Int {
        check(permission == android.Manifest.permission.BLUETOOTH_CONNECT)
        return if (connectGranted) 0 else -1
    }
}
class ComponentName(context: Context, cls: Class<*>)
class SharedPreferences {
    val values = mutableMapOf<String, Any>()
    fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    fun getString(key: String, default: String?) = values[key] as? String ?: default
    fun edit() = Editor()
    inner class Editor {
        fun putInt(key: String, value: Int) = apply { values[key] = value }
        fun putLong(key: String, value: Long) = apply { values[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        fun putString(key: String, value: String?) = apply { if (value != null) values[key] = value else values.remove(key) }
        fun remove(key: String) = apply { values.remove(key) }
        fun apply() {}
    }
}
