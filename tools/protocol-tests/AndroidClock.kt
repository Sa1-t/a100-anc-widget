package android.os
object SystemClock {
    var elapsedOffset = 0L
    fun uptimeMillis() = System.nanoTime() / 1_000_000
    fun elapsedRealtime() = uptimeMillis() + elapsedOffset
}
