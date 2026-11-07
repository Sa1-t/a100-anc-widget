package com.alpha.ancwidget
import android.content.Context
object R {
    object layout { const val activity_main = 1 }
    object id { const val btn_add_widget = 2 }
}
class AncWidgetProvider
// Settings Views are compiled with the real SDK by check_phase2; pin tests isolate the pin flow.
object WidgetSettingsUi { fun bind(activity: android.app.Activity) {}
    fun flush(activity: android.app.Activity) {} }
object BatteryRefreshScheduler { fun sync(context: Context) {} }

class DeviceControlsUi(activity: android.app.Activity) { fun onResume() {}
 fun onPause() {} }
