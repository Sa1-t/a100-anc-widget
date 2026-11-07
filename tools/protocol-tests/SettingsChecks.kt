package com.alpha.ancwidget

import android.content.Context

fun main() {
    val context = Context()
    val slots = WidgetSettings.ColorSlot.values()
    check(slots.map { it.key }.toSet() == setOf("battery_high_color", "battery_medium_color",
        "battery_low_color", "mode_normal_color", "mode_anc_color", "mode_transparency_color"))
    check(slots.map { WidgetSettings.color(context, it) } == listOf(
        0xFF65CC88.toInt(), 0xFFE8BC60.toInt(), 0xFFEE7B82.toInt(),
        0xFFD3D9E2.toInt(), 0xFF8DBBFA.toInt(), 0xFF78D2BD.toInt()))
    check(WidgetSettings.opacity(context) == 80 && WidgetSettings.modeSwitchEnabled(context))
    check(WidgetSettings.KEY_OPACITY == "card_opacity_percent")
    check(WidgetSettings.KEY_MODE_SWITCH == "mode_switch_enabled")
    context.prefs.edit().putString(MainActivity.KEY_MAC, "selected-device")
        .putInt("last_set_mode", 1).putInt("battery_left", 75).apply()

    // Each user edit persists and immediately requests all widgets to be redrawn.
    var updates = AncWidgetProvider.updates
    for ((index, slot) in slots.withIndex()) {
        val color = WidgetSettings.swatches[(index + 2) % WidgetSettings.swatches.size].color
        WidgetSettings.setColor(context, slot, color)
        check(WidgetSettings.color(context, slot) == color)
        check(context.prefs.getInt(slot.key, 0) == color)
        check(AncWidgetProvider.updates == ++updates)
    }
    val palette = WidgetSettings.palette(context)
    for (level in listOf(0, 19, 20, 49, 50, 100)) {
        val slot = if (level >= 50) WidgetSettings.ColorSlot.HIGH
                   else if (level >= 20) WidgetSettings.ColorSlot.MEDIUM else WidgetSettings.ColorSlot.LOW
        check(WidgetAppearance.batteryColor(level, palette = palette) == WidgetSettings.color(context, slot))
    }
    for (level in listOf(null, -1, 101))
        check(WidgetAppearance.batteryColor(level, palette = palette) == WidgetAppearance.NEUTRAL)
    for ((mode, slot) in listOf(AmbientMode.NORMAL to WidgetSettings.ColorSlot.NORMAL,
        AmbientMode.ANC to WidgetSettings.ColorSlot.ANC, AmbientMode.TRANSPARENCY to WidgetSettings.ColorSlot.TRANSPARENCY))
        check(WidgetAppearance.modeColor(mode, palette) == WidgetSettings.color(context, slot))

    for ((input, expected) in listOf(-10 to 40, 39 to 40, 40 to 40, 80 to 80, 100 to 100, 101 to 100)) {
        val changed = WidgetSettings.opacity(context) != expected
        WidgetSettings.setOpacity(context, input)
        check(WidgetSettings.opacity(context) == expected)
        check(context.prefs.getInt(WidgetSettings.KEY_OPACITY, 0) == expected)
        if (changed) updates++
        check(AncWidgetProvider.updates == updates)
    }
    check(WidgetSettings.opacityAlpha(0) == 102 && WidgetSettings.opacityAlpha(40) == 102)
    check(WidgetSettings.opacityAlpha(80) == 204 && WidgetSettings.opacityAlpha(100) == 255)
    check(WidgetSettings.opacityAlpha(110) == 255)
    for ((stored, expected) in listOf(-1 to 40, 500 to 100)) {
        context.prefs.edit().putInt(WidgetSettings.KEY_OPACITY, stored).apply()
        check(WidgetSettings.opacity(context) == expected)
    }
    WidgetSettings.setOpacity(context, 67)
    WidgetSettings.setModeSwitchEnabled(context, false)
    check(!WidgetSettings.modeSwitchEnabled(context))
    check(AncWidgetProvider.updates == updates + 2)
    // Fresh reader from persisted preferences, as used after Activity/process recreation.
    val reopened = Context().apply { prefs.values.putAll(context.prefs.values) }
    check(WidgetSettings.opacity(reopened) == 67 && !WidgetSettings.modeSwitchEnabled(reopened))
    check(WidgetSettings.palette(reopened) == palette)
    check(reopened.prefs.getString(MainActivity.KEY_MAC, null) == "selected-device")
    check(reopened.prefs.getInt("last_set_mode", -1) == 1 && BatteryStore.read(reopened).left == 75)
    reopened.prefs.edit().putInt(WidgetSettings.ColorSlot.HIGH.key, 123).apply()
    check(WidgetSettings.color(reopened, WidgetSettings.ColorSlot.HIGH) == 0xFF00007B.toInt())
    val beforeRepeat = AncWidgetProvider.updates
    WidgetSettings.setOpacity(context, 67)
    WidgetSettings.setModeSwitchEnabled(context, false)
    WidgetSettings.setColor(context, WidgetSettings.ColorSlot.HIGH, WidgetSettings.color(context, WidgetSettings.ColorSlot.HIGH))
    check(AncWidgetProvider.updates == beforeRepeat)
    WidgetSettings.resetAppearance(context)
    check(AncWidgetProvider.updates == beforeRepeat + 1)
    check(WidgetSettings.opacity(context) == 80)
    check(slots.all { WidgetSettings.color(context, it) == it.defaultColor })
    check(!WidgetSettings.modeSwitchEnabled(context))
    check(context.prefs.getString(MainActivity.KEY_MAC, null) == "selected-device")
    check(AncModeController.lastConfirmedMode(context) == AmbientMode.ANC && BatteryStore.read(context).left == 75)
    println("PASS settings: six original defaults/keys, selected palette/thresholds, unknown neutral, 40–100% clamping and alpha, persistence, immediate widget updates; device/mode/battery retained")
}
