package com.alpha.ancwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object BatteryRefreshScheduler {
    private const val WORK_NAME = "a100-widget-battery"

    fun hasWidgets(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, AncWidgetProvider::class.java)).isNotEmpty()

    fun sync(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (hasWidgets(context)) {
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(BatteryRefreshWorker::class.java, 15, TimeUnit.MINUTES).build())
        } else {
            manager.cancelUniqueWork(WORK_NAME)
        }
        BatteryMonitorService.sync(context)
    }
}

/** A missed reading completes this period. Never retry, toast, or send a mode command. */
class BatteryRefreshWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!isStopped && BatteryRefreshScheduler.hasWidgets(applicationContext)) {
            // Share the click guard. If a user operation owns the connection, skip this period.
            AncModeController.runIfIdle {
                if (!isStopped) AncModeController.refreshBattery(applicationContext, background = true)
            }
        }
        return Result.success()
    }
}
