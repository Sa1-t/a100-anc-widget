package com.alpha.ancwidget

import android.content.Context
import java.text.DateFormat
import java.util.Date

/** Persist last known values across missed broadcasts, offline periods and widget resizing. */
object BatteryStore {
    private const val PREFIX = "battery_"
    private const val AVAILABLE = "battery_available"
    private const val UPDATED = "battery_updated"
    private val fields = listOf("left", "right", "case")

    data class Snapshot(
        val left: Int?, val right: Int?, val caseLevel: Int?, val updatedAt: Long
    ) {
        /** Lower of the two earbuds; null unless both are known, so a stale ear never hides the other. */
        val systemLevel: Int? get() = if (left != null && right != null) minOf(left, right) else null

        fun caption(): String = if (updatedAt == 0L) "電量尚未讀取" else
            "上次電量 · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatedAt))}"
    }

    fun read(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        fun level(field: String) = prefs.getInt(PREFIX + field, -1)
            .takeIf { it in 0..100 }
        return Snapshot(level("left"), level("right"), level("case"),
            prefs.getLong(UPDATED, 0L))
    }

    fun save(context: Context, state: BatteryState) {
        val editor = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
        fields.zip(listOf(state.left, state.right, state.caseLevel)).forEach { (field, value) ->
            editor.putInt(PREFIX + field, value ?: -1)
        }
        editor.putBoolean(AVAILABLE, true).putLong(UPDATED, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) {
        val editor = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
        fields.forEach { editor.remove(PREFIX + it) }
        // Also discard legacy aggregate keys when selecting a new device; never read them.
        editor.remove(UPDATED).remove(AVAILABLE).remove("battery_system_level")
            .remove("battery_system_updated").remove("battery_system_fallback").apply()
    }
}
