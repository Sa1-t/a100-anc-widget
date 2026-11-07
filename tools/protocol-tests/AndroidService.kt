package android.app

import android.content.Context
import android.content.Intent
import android.os.IBinder

abstract class Service : Context() {
    var foregroundCalls = 0
    var foregroundType: Int? = null
    var stopped = false
    var foregroundRemoved = false
    open fun onCreate() {}
    open fun onDestroy() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    abstract fun onBind(intent: Intent?): IBinder?
    fun startForeground(id: Int, notification: Notification) { foregroundCalls++ }
    fun startForeground(id: Int, notification: Notification, type: Int) {
        foregroundCalls++; foregroundType = type
    }
    fun stopSelf() { stopped = true }
    fun stopForeground(flags: Int) { foregroundRemoved = true }
    companion object {
        const val START_STICKY = 1
        const val START_NOT_STICKY = 2
        const val STOP_FOREGROUND_REMOVE = 1
    }
}
class Notification {
    class Builder(context: Context, channel: String) {
        fun setSmallIcon(icon: Int) = this
        fun setContentTitle(title: String) = this
        fun setOngoing(value: Boolean) = this
        fun setOnlyAlertOnce(value: Boolean) = this
        fun setShowWhen(value: Boolean) = this
        fun setLocalOnly(value: Boolean) = this
        fun setCategory(value: String) = this
        fun setPriority(value: Int) = this
        fun build() = Notification()
    }
    companion object { const val CATEGORY_SERVICE = "service"; const val PRIORITY_MIN = -2 }
}
class NotificationChannel(id: String, name: String, importance: Int) {
    fun setShowBadge(value: Boolean) {}
}
class NotificationManager {
    fun createNotificationChannel(channel: NotificationChannel) {}
    companion object { const val IMPORTANCE_MIN = 1 }
}
