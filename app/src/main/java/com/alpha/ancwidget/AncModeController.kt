package com.alpha.ancwidget

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

enum class AmbientMode(val value: Int, val label: String) {
    NORMAL(0x00, "一般"),
    ANC(0x01, "ANC"),
    TRANSPARENCY(0x02, "通透");

    fun next(): AmbientMode = when (this) {
        NORMAL -> ANC
        ANC -> TRANSPARENCY
        TRANSPARENCY -> NORMAL
    }
}

/** Shared by widget and background refresh. Stored modes describe confirmed SWITCHes, not live earbud state. */
object AncModeController {
    private const val KEY_MODE = "last_set_mode"
    private val busy = AtomicBoolean(false)
    private val refreshGate = BatteryRefreshGate()

    /** One user operation at a time, including refresh. No receiver waits in an unbounded queue. */
    fun launch(operation: () -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        Thread {
            try { operation() } finally { busy.set(false) }
        }.start()
        return true
    }

    /** Synchronous Worker entry point; a busy connection is skipped rather than queued. */
    fun runIfIdle(operation: () -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try { operation() } finally { busy.set(false) }
        return true
    }

    fun lastConfirmedMode(context: Context): AmbientMode? {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val value = prefs.getInt(KEY_MODE, -1)
        return AmbientMode.values().firstOrNull { it.value == value }
    }

    fun widgetLabel(context: Context): String =
        lastConfirmedMode(context)?.let { "上次設定：${it.label}" } ?: "切換 ANC"

    /**
     * Null target means advance from the last confirmed mode (initially Normal).
     * Serialize the entire read -> SWITCH -> persist -> widget update sequence so
     * concurrent widget clicks cannot advance from the same old value.
     * Call only on a background thread.
     */
    @Synchronized
    fun changeMode(context: Context, target: AmbientMode? = null): GaiaClient.Result {
        // Recheck after acquiring the operation guard: a queued/stale click must not send SWITCH.
        if (!WidgetSettings.modeSwitchEnabled(context)) {
            return GaiaClient.Result.Failure("模式切換已關閉")
        }
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val mac = prefs.getString(MainActivity.KEY_MAC, null)
        val mode = target ?: (lastConfirmedMode(context) ?: AmbientMode.NORMAL).next()
        val result = if (mac.isNullOrEmpty()) {
            GaiaClient.Result.Failure("請先選取耳機")
        } else {
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = manager.adapter
                if (adapter == null || !adapter.isEnabled) {
                    GaiaClient.Result.Failure("請先開啟藍牙")
                } else {
                    // One selected SPP endpoint is sufficient, including one earbud.
                    // Battery availability, in-case and wear state never gate SWITCH.
                    GaiaClient.setMode(adapter, mac, mode) { state ->
                        BatteryStore.save(context, state)
                    }
                }
            } catch (_: SecurityException) {
                GaiaClient.Result.Failure("缺少藍牙權限")
            }
        }
        if (result is GaiaClient.Result.Success) {
            prefs.edit().putInt(KEY_MODE, result.mode.value).apply()
        }
        AncWidgetProvider.updateAll(context, when (result) {
            is GaiaClient.Result.Success -> "已設定：${result.mode.label} ✓"
            is GaiaClient.Result.Failure -> "失敗：${result.message}"
        })
        return result
    }

    /** Explicit foreground mono read/write; serialized with ANC and battery operations. */
    @Synchronized
    fun mono(context: Context, target: Boolean? = null): GaiaClient.MonoResult {
        val mac = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_MAC, null)
        val result = try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            when {
                mac.isNullOrEmpty() -> GaiaClient.MonoResult(null, "請先用「加入到桌面」選取耳機")
                adapter == null || !adapter.isEnabled -> GaiaClient.MonoResult(null, "請先開啟藍牙")
                else -> GaiaClient.mono(adapter, mac, target) { BatteryStore.save(context, it) }
            }
        } catch (_: SecurityException) {
            GaiaClient.MonoResult(null, "缺少藍牙權限")
        }
        AncWidgetProvider.updateAll(context)
        return result
    }

    /** Shares the SWITCH lock, transport and battery cache, but never touches KEY_MODE. */
    @Synchronized
    fun refreshBattery(context: Context, background: Boolean = false): GaiaClient.BatteryResult {
        val mac = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_MAC, null)
        val result = try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            when {
                mac.isNullOrEmpty() -> GaiaClient.BatteryResult(false, "請先選取耳機")
                adapter == null || !adapter.isEnabled -> GaiaClient.BatteryResult(false, "請先開啟藍牙")
                background && !refreshGate.tryAcquire(mac, SystemClock.elapsedRealtime()) ->
                    GaiaClient.BatteryResult(false, "剛剛已嘗試更新，保留上次電量")
                else -> GaiaClient.readBattery(adapter, mac) { BatteryStore.save(context, it) }
            }
        } catch (_: SecurityException) {
            GaiaClient.BatteryResult(false, "缺少藍牙權限")
        }
        AncWidgetProvider.updateAll(context)
        return result
    }

}
