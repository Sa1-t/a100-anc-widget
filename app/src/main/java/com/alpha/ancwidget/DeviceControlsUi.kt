package com.alpha.ancwidget

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Foreground controls use the same bounded transport guard. Opening this panel never connects. */
class DeviceControlsUi(private val activity: Activity) {
    private val context = activity.applicationContext
    private val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val container = activity.findViewById<LinearLayout>(R.id.device_controls)
    private val status = label("耳機狀態", 20f)
    private val details = label("")
    private val feedback = label("")
    private val buttons = mutableListOf<Button>()
    private var active = false
    private var working = false
    private var generation = 0
    private var mono: Boolean? = null
    private var binding = false
    private val monoSwitch: Switch
    private val redraw = Runnable { if (active) render() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // One render per batch of changed preference keys; no polling or Bluetooth reads.
        handler.removeCallbacks(redraw)
        handler.post(redraw)
    }

    init {
        feedback.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        button("更新電量") { runOperation { AncModeController.refreshBattery(context).message } }
        label("模式為本 App 上次確認的設定；耳機觸控或其他 App 的變更不會自動同步。")
        label("單聲道音訊", 18f)
        label("將左右聲道混合，兩耳播放相同內容；與只戴一耳不同。先讀取狀態才能切換。")
        button("讀取單聲道狀態") { runMono(null) }
        monoSwitch = Switch(activity).apply {
            text = "單聲道：尚未讀取"
            minHeight = dp(56)
            isEnabled = false
            setOnCheckedChangeListener { _, checked ->
                if (!binding) {
                    // Keep the last readback visible while the request is pending.
                    render()
                    runMono(checked)
                }
            }
        }
        container.addView(monoSwitch)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    private fun label(value: String, size: Float = 14f) = TextView(activity).apply {
        text = value
        textSize = size
        setPadding(0, dp(6), 0, dp(6))
        container.addView(this)
    }
    private fun button(title: String, action: () -> Unit): Button = Button(activity).apply {
        text = title
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener { action() }
        container.addView(this)
        buttons.add(this)
    }

    fun onResume() {
        active = true
        prefs.registerOnSharedPreferenceChangeListener(listener)
        render()
    }

    fun onPause() {
        active = false
        generation++
        mono = null // Never treat an old foreground session as current device state.
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        handler.removeCallbacks(redraw)
    }

    private fun render() {
        val battery = BatteryStore.read(context)
        status.text = prefs.getString(MainActivity.KEY_NAME, null) ?: "尚未選取耳機"
        details.text = "左耳 ${BatteryState.display(battery.left)} · 右耳 ${BatteryState.display(battery.right)} · " +
            "充電盒 ${BatteryState.display(battery.caseLevel)}\n${battery.caption()}\n" +
            AncModeController.widgetLabel(context)
        buttons.forEach { it.isEnabled = !working }
        binding = true
        monoSwitch.isChecked = mono == true
        monoSwitch.isEnabled = !working && mono != null
        monoSwitch.text = when (mono) {
            true -> "單聲道：已確認開啟"
            false -> "單聲道：已確認關閉"
            null -> "單聲道：尚未確認，請讀取"
        }
        binding = false
    }

    private fun runMono(target: Boolean?) {
        val requestGeneration = generation
        runOperation {
            val result = try { AncModeController.mono(context, target) }
                catch (_: RuntimeException) { GaiaClient.MonoResult(null, "單聲道結果未確認，請重新讀取") }
            handler.post {
                if (active && generation == requestGeneration) {
                    mono = result.enabled
                    render()
                }
            }
            result.message
        }
    }

    private fun runOperation(operation: () -> String) {
        if (working) return
        working = true
        feedback.text = "處理中…"
        render()
        val requestGeneration = generation
        val started = AncModeController.launch {
            val message = try { operation() } catch (_: RuntimeException) { "操作未完成，請重試" }
            handler.post {
                working = false
                if (active && generation == requestGeneration) feedback.text = message
                else feedback.text = "請重新讀取耳機狀態"
                if (active) render()
            }
        }
        if (!started) {
            working = false
            feedback.text = "操作進行中，請稍候再試"
            render()
        }
    }
}
