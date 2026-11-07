package android.appwidget

import android.content.ComponentName
import android.content.Context

class AppWidgetManager {
    companion object {
        var widgetIds = intArrayOf()
        fun getInstance(context: Context) = AppWidgetManager()
    }
    fun getAppWidgetIds(component: ComponentName) = widgetIds
}
