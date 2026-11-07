package com.alpha.ancwidget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Keeps only a dynamic receiver alive. RFCOMM is opened briefly by the shared refresh path. */
class BatteryMonitorService : Service() {
    companion object {
        // AOSP @hide constants: literal strings need neither reflection nor hidden API calls.
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val CHANNEL = "headphone-battery-monitor"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "BatteryMonitor"

        private fun permitted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        private fun selectedMac(context: Context): String? =
            context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_MAC, null)?.takeIf { it.isNotEmpty() }

        fun sync(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            if (!BatteryRefreshScheduler.hasWidgets(context) || !permitted(context) || selectedMac(context) == null) {
                context.stopService(intent)
                return
            }
            try {
                context.startForegroundService(intent)
            } catch (_: SecurityException) {
                // Permission may have been revoked since the check. Periodic work remains available.
            } catch (_: IllegalStateException) {
                // Android 12+ can reject a background start outside an exempt user interaction.
                // The next Activity/widget interaction can recover; never spin or retry here.
                Log.i(TAG, "Background monitor start deferred by Android")
            }
        }
    }

    @Volatile private var active = false
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active || intent.action != ACTION_BATTERY_LEVEL_CHANGED || !permitted(context)) return
            if (!BatteryRefreshScheduler.hasWidgets(context)) {
                stopSelf()
                return
            }
            val mac = selectedMac(context) ?: return
            val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
            if (level !in 0..100) return
            try {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
                if (!mac.equals(device?.address, ignoreCase = true)) return
            } catch (_: SecurityException) {
                return
            }
            // The aggregate percentage only validates this trigger; GAIA supplies display values.
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Validate runtime prerequisites BEFORE startForeground, including sticky process restarts.
        if (!BatteryRefreshScheduler.hasWidgets(this) || !permitted(this) || selectedMac(this) == null) {
            stopSelf()
            return
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, "耳機電量監聽", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) })
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_battery_monitor)
                .setContentTitle("正在監聽耳機電量變化")
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setLocalOnly(true)
                .setCategory(Notification.CATEGORY_SERVICE).setPriority(Notification.PRIORITY_MIN)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            val filter = IntentFilter(ACTION_BATTERY_LEVEL_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            registered = true
            active = true
            // Not sticky: registering cannot return the current HFP value. Read GAIA once initially.
            refresh()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun refresh() {
        val started = AncModeController.launch {
            if (active && BatteryRefreshScheduler.hasWidgets(this) && permitted(this)) {
                // No commands, no retries; closes the socket even if unavailable or occupied.
                AncModeController.refreshBattery(this, background = true)
            }
        }
        if (!started) AncWidgetProvider.updateAll(this) // Keep the existing GAIA snapshot while busy.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (active) START_STICKY else START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        if (registered) {
            unregisterReceiver(receiver)
            registered = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

/** Restore the widget-scoped monitor after reboot/update without relying on a periodic background start. */
class BatteryMonitorRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BatteryRefreshScheduler.sync(context)
        }
    }
}
