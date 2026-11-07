package com.alpha.ancwidget

import android.content.Context
import kotlin.math.roundToInt

/** Shared settings for every widget; never changes device, battery or confirmed mode state. */
object WidgetSettings {
    const val KEY_OPACITY = "card_opacity_percent"
    const val KEY_MODE_SWITCH = "mode_switch_enabled"
    const val MIN_OPACITY = 40
    const val MAX_OPACITY = 100
    const val DEFAULT_OPACITY = 80

    enum class ColorSlot(val key: String, val label: String, val defaultColor: Int) {
        HIGH("battery_high_color", "高電量（≥50%）", WidgetAppearance.GREEN),
        MEDIUM("battery_medium_color", "中電量（20–49%）", WidgetAppearance.AMBER),
        LOW("battery_low_color", "低電量（<20%）", WidgetAppearance.RED),
        NORMAL("mode_normal_color", "一般模式", WidgetAppearance.modeColor(AmbientMode.NORMAL)),
        ANC("mode_anc_color", "ANC 模式", WidgetAppearance.modeColor(AmbientMode.ANC)),
        TRANSPARENCY("mode_transparency_color", "通透模式", WidgetAppearance.modeColor(AmbientMode.TRANSPARENCY))
    }

    data class Swatch(val name: String, val color: Int)
    val swatches = listOf(
        Swatch("綠", WidgetAppearance.GREEN), Swatch("黃", WidgetAppearance.AMBER),
        Swatch("紅", WidgetAppearance.RED), Swatch("灰白", ColorSlot.NORMAL.defaultColor),
        Swatch("藍", ColorSlot.ANC.defaultColor), Swatch("青綠", ColorSlot.TRANSPARENCY.defaultColor),
        Swatch("紫", 0xFFC2A4F5.toInt()), Swatch("橙", 0xFFF3AB70.toInt())
    )

    private fun prefs(context: Context) = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    // Color keys accept any opaque RGB value, including values outside the preset palette.
    fun color(context: Context, slot: ColorSlot): Int =
        prefs(context).getInt(slot.key, slot.defaultColor) or 0xFF000000.toInt()

    enum class Content(val label: String) {
        MODE("模式（ANC 狀態）"), LEFT("左耳電量"), RIGHT("右耳電量"), CASE("充電盒電量"),
        SYSTEM("系統電量（取低者）")
    }

    // Shared across all widgets of the same size. Two slots may select the same content.
    enum class ContentSlot(val key: String, val label: String, val defaultContent: Content) {
        COMPACT("compact_content", "1×1", Content.MODE),
        WIDE_FIRST("wide_first_content", "2×1 左側", Content.LEFT),
        WIDE_SECOND("wide_second_content", "2×1 右側", Content.RIGHT),
        TALL_FIRST("tall_first_content", "1×2 上方", Content.CASE),
        TALL_SECOND("tall_second_content", "1×2 下方", Content.MODE)
    }

    fun content(context: Context, slot: ContentSlot): Content =
        Content.values().firstOrNull { it.name == prefs(context).getString(slot.key, null) }
            ?: slot.defaultContent

    fun setContent(context: Context, slot: ContentSlot, content: Content) {
        if (content(context, slot) == content) return
        prefs(context).edit().putString(slot.key, content.name).apply()
        AncWidgetProvider.updateAll(context)
    }

    fun palette(context: Context) = WidgetAppearance.Palette(
        high = color(context, ColorSlot.HIGH), medium = color(context, ColorSlot.MEDIUM),
        low = color(context, ColorSlot.LOW), normal = color(context, ColorSlot.NORMAL),
        anc = color(context, ColorSlot.ANC), transparency = color(context, ColorSlot.TRANSPARENCY)
    )

    fun opacity(context: Context): Int = prefs(context).getInt(KEY_OPACITY, DEFAULT_OPACITY)
        .coerceIn(MIN_OPACITY, MAX_OPACITY)

    fun opacityAlpha(percent: Int): Int =
        (percent.coerceIn(MIN_OPACITY, MAX_OPACITY) * 255 / 100f).roundToInt()

    fun modeSwitchEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MODE_SWITCH, true)

    fun setColor(context: Context, slot: ColorSlot, color: Int) {
        val opaque = color or 0xFF000000.toInt()
        if (color(context, slot) == opaque) return
        prefs(context).edit().putInt(slot.key, opaque).apply()
        AncWidgetProvider.updateAll(context)
    }

    fun setOpacity(context: Context, percent: Int) {
        val bounded = percent.coerceIn(MIN_OPACITY, MAX_OPACITY)
        if (opacity(context) == bounded) return
        prefs(context).edit().putInt(KEY_OPACITY, bounded).apply()
        AncWidgetProvider.updateAll(context)
    }

    fun resetAppearance(context: Context) {
        val editor = prefs(context).edit()
        ColorSlot.values().forEach { editor.remove(it.key) }
        editor.remove(KEY_OPACITY).apply()
        AncWidgetProvider.updateAll(context)
    }

    fun setModeSwitchEnabled(context: Context, enabled: Boolean) {
        if (modeSwitchEnabled(context) == enabled) return
        prefs(context).edit().putBoolean(KEY_MODE_SWITCH, enabled).apply()
        AncWidgetProvider.updateAll(context)
    }
}
