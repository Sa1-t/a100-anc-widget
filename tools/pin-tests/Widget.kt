package android.appwidget
import android.content.ComponentName
import android.content.Context
import com.alpha.ancwidget.MainActivity
class AppWidgetManager(val context: Context) {
    companion object {
        var supported = true
        var accepted = true
        var throws = false
        var calls = 0
        fun getInstance(context: Context) = AppWidgetManager(context)
    }
    val isRequestPinAppWidgetSupported get() = supported
    fun requestPinAppWidget(provider: ComponentName, extras: Any?, callback: Any?): Boolean {
        check(!context.prefs.getString(MainActivity.KEY_MAC, null).isNullOrEmpty()) { "Selection must be saved before launcher is called" }
        calls++
        if (throws) throw IllegalStateException("launcher rejected request")
        return accepted
    }
}
