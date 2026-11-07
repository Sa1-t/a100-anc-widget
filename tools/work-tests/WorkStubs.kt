package androidx.work

import android.content.Context
import java.util.concurrent.TimeUnit

/** Test boundary only. These signatures do not replace the real WorkManager dependency. */
class WorkerParameters
abstract class Worker(val applicationContext: Context, parameters: WorkerParameters) {
    var isStopped = false
    abstract fun doWork(): Result
    class Result private constructor() {
        companion object { fun success() = Result() }
    }
}
enum class ExistingPeriodicWorkPolicy { KEEP }
class PeriodicWorkRequest private constructor(val worker: Class<out Worker>, val intervalMs: Long) {
    class Builder(private val worker: Class<out Worker>, private val interval: Long, private val unit: TimeUnit) {
        fun build() = PeriodicWorkRequest(worker, unit.toMillis(interval))
    }
}
class WorkManager {
    companion object {
        val instance = WorkManager()
        fun getInstance(context: Context) = instance
    }
    val requests = mutableMapOf<String, PeriodicWorkRequest>()
    fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
        requests.putIfAbsent(name, request)
    }
    fun cancelUniqueWork(name: String) { requests.remove(name) }
}
