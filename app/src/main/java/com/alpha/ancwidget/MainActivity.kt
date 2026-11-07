package com.alpha.ancwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Shared device selection for initial setup and pinning, plus local widget settings. */
class MainActivity : Activity() {
    companion object {
        const val PREFS = "anc_widget_prefs"
        const val KEY_MAC = "device_mac"
        const val KEY_NAME = "device_name"
        private const val REQ_PERMISSIONS = 1
    }

    private lateinit var controls: DeviceControlsUi
    private enum class SelectionPurpose { SELECT_ONLY, PIN_WIDGET }
    private var pendingSelection: SelectionPurpose? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.version_label).text = "版本 ${info.versionName}"
        } catch (_: Exception) {
            // Keep the layout default if the package info cannot be read.
        }
        findViewById<Button>(R.id.btn_add_widget).setOnClickListener { addWidget() }
        WidgetSettingsUi.bind(this)
        controls = DeviceControlsUi(this)
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MAC, null).isNullOrEmpty()) {
            selectHeadphones(SelectionPurpose.SELECT_ONLY)
        }
    }

    override fun onResume() {
        super.onResume()
        controls.onResume()
        BatteryRefreshScheduler.sync(this)
    }

    override fun onPause() {
        WidgetSettingsUi.flush(this)
        controls.onPause()
        super.onPause()
    }

    private fun addWidget() {
        selectHeadphones(SelectionPurpose.PIN_WIDGET)
    }

    private fun selectHeadphones(purpose: SelectionPurpose) {
        if (pendingSelection != null) {
            // An explicit add click can reuse the setup already awaiting permission/selection.
            if (purpose == SelectionPurpose.PIN_WIDGET) pendingSelection = purpose
            return
        }
        pendingSelection = purpose
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
                return
            }
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getString(KEY_MAC, null).isNullOrEmpty()) {
            finishSelection()
            return
        }
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null) {
                pendingSelection = null
                message("此裝置不支援藍牙")
                return
            }
            if (!adapter.isEnabled) {
                pendingSelection = null
                message("請先開啟藍牙，再選取已配對耳機")
                return
            }
            val devices = adapter.bondedDevices.sortedWith(
                compareByDescending<android.bluetooth.BluetoothDevice> {
                    it.name?.contains("A100", ignoreCase = true) == true
                }.thenBy { it.name ?: it.address }
            )
            if (devices.isEmpty()) {
                pendingSelection = null
                message("請先在系統藍牙設定配對 Melomania A100")
                return
            }
            AlertDialog.Builder(this)
                .setTitle("選取 Melomania A100")
                .setItems(devices.map { "${it.name ?: "未命名裝置"}\n${it.address}" }.toTypedArray()) { _, index ->
                    val device = devices[index]
                    BatteryStore.clear(this)
                    prefs.edit().putString(KEY_MAC, device.address).putString(KEY_NAME, device.name).apply()
                    finishSelection()
                }
                .setNegativeButton("取消") { _, _ -> cancelSelection() }
                .setOnCancelListener { cancelSelection() }
                .show()
        } catch (_: SecurityException) {
            pendingSelection = null
            message("需要藍牙權限才能選取耳機")
        }
    }

    private fun finishSelection() {
        val purpose = pendingSelection
        pendingSelection = null
        if (purpose == SelectionPurpose.PIN_WIDGET) pinWidget()
    }

    private fun cancelSelection() {
        val purpose = pendingSelection ?: return
        pendingSelection = null
        message(if (purpose == SelectionPurpose.PIN_WIDGET)
            "已取消加入；再次點「加入到桌面」可重新選取耳機" else
            "已取消選取耳機；下次啟動 App 或點「加入到桌面」可重新選取")
    }

    private fun pinWidget() {
        // Also recover monitoring for existing widgets after permission was granted.
        BatteryRefreshScheduler.sync(this)
        val manager = AppWidgetManager.getInstance(this)
        val manual = "耳機選擇已儲存。請長按桌面空白處 → 小工具，加入「A100 ANC 切換」"
        try {
            if (manager.isRequestPinAppWidgetSupported) {
                val requested = manager.requestPinAppWidget(ComponentName(this, AncWidgetProvider::class.java), null, null)
                message(if (requested) "已送出加入請求，請在系統確認視窗點「新增」" else
                    "系統未接受加入請求。$manual")
            } else {
                message("此桌面不支援從 App 加入小工具。$manual")
            }
        } catch (_: RuntimeException) {
            message("無法開啟系統加入視窗。$manual")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val purpose = pendingSelection ?: return
            pendingSelection = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                selectHeadphones(purpose)
            } else {
                message(if (purpose == SelectionPurpose.PIN_WIDGET)
                    "需要藍牙權限，請授權後再加入到桌面" else
                    "需要藍牙權限才能選取耳機；下次啟動 App 或點「加入到桌面」可重試")
            }
        }
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
