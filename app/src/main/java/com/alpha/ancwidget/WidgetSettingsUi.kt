package com.alpha.ancwidget

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.text.InputFilter
import android.text.InputType
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Plain framework Views, with large touch targets and a selected mark on each palette row. */
object WidgetSettingsUi {
    private class OpacityCommit(val activity: Activity, val slider: SeekBar) : Runnable {
        var pending = false
        fun schedule() {
            if (pending) return
            pending = true
            slider.postDelayed(this, 100L)
        }
        override fun run() {
            if (!pending) return
            pending = false
            WidgetSettings.setOpacity(activity, slider.progress + WidgetSettings.MIN_OPACITY)
        }
        fun flush() { slider.removeCallbacks(this); run() }
    }

    // Commit the last drag value even when the Activity leaves before the next preview frame.
    fun flush(activity: Activity) {
        (activity.findViewById<SeekBar>(R.id.card_opacity).tag as? OpacityCommit)?.flush()
    }

    fun bind(activity: Activity) {
        flush(activity)
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
        val container = activity.findViewById<LinearLayout>(R.id.ring_colors)
        container.removeAllViews()
        for (slot in WidgetSettings.ColorSlot.values()) {
            container.addView(TextView(activity).apply {
                text = slot.label
                textSize = 14f
                setPadding(0, dp(12), 0, dp(4))
            })
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val buttons = mutableListOf<Pair<Button, WidgetSettings.Swatch>>()
            val custom = Button(activity)
            fun showSelection() {
                val selectedColor = WidgetSettings.color(activity, slot)
                for ((button, swatch) in buttons) {
                    val selected = swatch.color == selectedColor
                    button.isSelected = selected
                    button.text = if (selected) "✓" else ""
                    button.contentDescription = "${slot.label}，${swatch.name}，" +
                        if (selected) "已選取" else "未選取"
                    button.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(swatch.color)
                        setStroke(dp(if (selected) 3 else 1), if (selected) Color.WHITE else Color.TRANSPARENT)
                    }
                }
                val customSelected = WidgetSettings.swatches.none { it.color == selectedColor }
                custom.isSelected = customSelected
                custom.text = if (customSelected) "✓" else "+"
                custom.contentDescription = "${slot.label}，自訂顏色" +
                    if (customSelected) "，已選取 #%06X".format(selectedColor and 0xFFFFFF)
                    else "，開啟顏色選擇器"
                custom.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(selectedColor)
                    setStroke(dp(if (customSelected) 3 else 1), if (customSelected) Color.WHITE else Color.TRANSPARENT)
                }
            }
            for (swatch in WidgetSettings.swatches) {
                val button = Button(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        marginEnd = dp(8)
                    }
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    backgroundTintList = null
                    setTextColor(Color.rgb(25, 28, 34))
                    textSize = 22f
                    setOnClickListener {
                        WidgetSettings.setColor(activity, slot, swatch.color)
                        showSelection()
                    }
                }
                buttons.add(button to swatch)
                row.addView(button)
            }
            custom.apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginEnd = dp(8)
                }
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                backgroundTintList = null
                setTextColor(Color.rgb(25, 28, 34))
                textSize = 22f
                setOnClickListener { chooseCustomColor(activity, slot) { showSelection() } }
            }
            row.addView(custom)
            showSelection()
            container.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(24))
                addView(row)
            })
        }
        val contents = activity.findViewById<LinearLayout>(R.id.compact_contents)
        contents.removeAllViews()
        for (slot in WidgetSettings.ContentSlot.values()) {
            val button = Button(activity).apply { minHeight = dp(48); isAllCaps = false }
            fun showContent() { button.text = "${slot.label}：${WidgetSettings.content(activity, slot).label}" }
            button.setOnClickListener {
                val options = WidgetSettings.Content.values()
                AlertDialog.Builder(activity).setTitle("${slot.label} 顯示內容")
                    .setSingleChoiceItems(options.map { it.label }.toTypedArray(),
                        options.indexOf(WidgetSettings.content(activity, slot))) { dialog, index ->
                        WidgetSettings.setContent(activity, slot, options[index])
                        showContent()
                        dialog.dismiss()
                    }.setNegativeButton("取消", null).show()
            }
            showContent()
            contents.addView(button)
        }
        val opacityLabel = activity.findViewById<TextView>(R.id.opacity_label)
        val opacity = activity.findViewById<SeekBar>(R.id.card_opacity)
        fun showOpacity(percent: Int) { opacityLabel.text = "卡片不透明度：$percent%" }
        opacity.setOnSeekBarChangeListener(null)
        val commit = OpacityCommit(activity, opacity)
        opacity.tag = commit
        opacity.max = WidgetSettings.MAX_OPACITY - WidgetSettings.MIN_OPACITY
        opacity.progress = WidgetSettings.opacity(activity) - WidgetSettings.MIN_OPACITY
        showOpacity(WidgetSettings.opacity(activity))
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val percent = progress + WidgetSettings.MIN_OPACITY
                showOpacity(percent)
                if (fromUser) commit.schedule()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) { commit.flush() }
        })
        container.addView(Button(activity).apply {
            text = "恢復預設外觀"
            minHeight = dp(48)
            setOnClickListener {
                AlertDialog.Builder(activity).setTitle("恢復預設外觀？")
                    .setMessage("恢復六個圓環色與 80% 不透明度；保留耳機、小尺寸內容與切換設定。")
                    .setPositiveButton("恢復") { _, _ ->
                        commit.flush()
                        WidgetSettings.resetAppearance(activity)
                        bind(activity)
                    }
                    .setNegativeButton("取消", null).show()
            }
        })
        activity.findViewById<Switch>(R.id.mode_switch_enabled).apply {
            isChecked = WidgetSettings.modeSwitchEnabled(activity)
            setOnCheckedChangeListener { _, checked -> WidgetSettings.setModeSwitchEnabled(activity, checked) }
        }
    }

    private fun chooseCustomColor(activity: Activity, slot: WidgetSettings.ColorSlot, applied: () -> Unit) {
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "#RRGGBB，例如 #65CC88"
            contentDescription = "${slot.label}，六位 Hex RGB 色碼"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(7))
            setText("#%06X".format(WidgetSettings.color(activity, slot) and 0xFFFFFF))
            selectAll()
        }
        val dialog = AlertDialog.Builder(activity).setTitle("自訂${slot.label}")
            .setMessage("輸入六位 Hex RGB 色碼，可省略 #。")
            .setView(input).setPositiveButton("套用", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hex = input.text.toString().trim().removePrefix("#")
                if (!hex.matches(Regex("[0-9a-fA-F]{6}"))) {
                    input.error = "請輸入六位 Hex 色碼，例如 #65CC88"
                } else {
                    WidgetSettings.setColor(activity, slot, hex.toInt(16) or 0xFF000000.toInt())
                    applied()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

}
