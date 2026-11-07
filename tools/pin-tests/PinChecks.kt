package com.alpha.ancwidget
import android.Manifest
import android.app.AlertDialog
import android.appwidget.AppWidgetManager as Host
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Build
import android.widget.Toast

private fun setup(granted: Boolean = true, selected: Boolean = true, supported: Boolean = true): MainActivity {
    Toast.messages.clear(); AlertDialog.pending = null
    Host.calls = 0; Host.supported = supported; Host.accepted = true; Host.throws = false
    return MainActivity().apply {
        connectGranted = granted
        service = BluetoothManager(BluetoothAdapter())
        if (selected) prefs.edit().putString(MainActivity.KEY_MAC, "00:11:22:33:44:55").apply()
        onCreate(null)
    }
}
private fun grant(activity: MainActivity, allowed: Boolean) {
    val permissions = checkNotNull(activity.requested)
    check(permissions.toList() == listOf(Manifest.permission.BLUETOOTH_CONNECT))
    activity.connectGranted = allowed
    activity.onRequestPermissionsResult(activity.requestCode, permissions, intArrayOf(if (allowed) 0 else -1))
}
fun main() {
    Build.VERSION.SDK_INT = 34
    // Three independent booleans give 8 paths: 4 permission/selection rows x 2 launchers.
    for (granted in listOf(true, false)) for (selected in listOf(true, false)) for (supported in listOf(true, false)) {
        val activity = setup(granted, selected, supported)
        activity.prefs.edit().putInt("battery_left", 88).apply()
        activity.button.click()
        if (!granted) {
            check(activity.requested != null && Host.calls == 0 && AlertDialog.pending == null)
            grant(activity, true) // continues automatically, no second button click
        } else check(activity.requested == null)
        if (!selected) {
            val dialog = checkNotNull(AlertDialog.pending)
            check(dialog.labels.single().contains("A100"))
            dialog.select(null, 0)
            check(BatteryStore.read(activity).left == null)
        }
        check(activity.prefs.getString(MainActivity.KEY_MAC, null) == "00:11:22:33:44:55")
        check(Toast.messages.size == 1)
        if (supported) {
            check(Host.calls == 1 && Toast.messages.single().contains("系統確認視窗"))
        } else {
            check(Host.calls == 0 && Toast.messages.single().contains("耳機選擇已儲存"))
            check(Toast.messages.single().contains("長按桌面"))
        }
        println("PASS pin CONNECT=$granted selected=$selected launcher=$supported -> " + Toast.messages.single())
    }
    for (accepted in listOf(false, true)) {
        val activity = setup()
        Host.accepted = accepted; Host.throws = accepted
        activity.button.click()
        check(Host.calls == 1 && Toast.messages.single().contains("長按桌面"))
    }
    for (selected in listOf(false, true)) {
        val activity = setup(granted = false, selected = selected)
        activity.button.click(); grant(activity, false)
        check(Host.calls == 0 && Toast.messages.single().contains("授權"))
    }
    val dismissedPermission = setup(granted = false)
    dismissedPermission.button.click()
    dismissedPermission.onRequestPermissionsResult(dismissedPermission.requestCode, emptyArray(), intArrayOf())
    check(Toast.messages.single().contains("授權"))
    for (cancel in listOf(false, true)) {
        val activity = setup(selected = false)
        activity.button.click()
        val dialog = checkNotNull(AlertDialog.pending)
        if (cancel) dialog.cancel(null) else dialog.negative(null, 0)
        check(Toast.messages.single().contains("取消") && Host.calls == 0)
    }
    for (scenario in listOf("off", "empty", "unsupported")) {
        val activity = setup() // Set the error state before starting selection, now shared with onCreate.
        activity.prefs.edit().remove(MainActivity.KEY_MAC).apply()
        val manager = activity.service as BluetoothManager
        when (scenario) {
            "off" -> manager.adapter!!.isEnabled = false
            "empty" -> manager.adapter!!.bondedDevices = emptySet()
            else -> manager.adapter = null
        }
        activity.button.click()
        check(Toast.messages.isNotEmpty() && Host.calls == 0)
    }
    Build.VERSION.SDK_INT = 26
    val legacy = setup(granted = false)
    legacy.button.click()
    check(legacy.requested == null && Host.calls == 1)
    println("PASS launcher false/exception, CONNECT denial/dismissal, selection cancellation, Bluetooth off/unsupported/no bonds, API 26")
}
