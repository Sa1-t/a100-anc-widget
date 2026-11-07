package com.alpha.ancwidget
import android.content.Context
object MainActivity {
    const val PREFS = "anc_widget_prefs"
    const val KEY_MAC = "device_mac"
}
object AncWidgetProvider {
    var lastText: String? = null
    var updates = 0
    fun updateAll(context: Context, text: String? = null) { lastText = text; updates++ }
}

object R { object drawable { const val ic_battery_monitor = 1 } }
