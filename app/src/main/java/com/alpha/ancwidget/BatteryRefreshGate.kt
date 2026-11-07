package com.alpha.ancwidget

/** Only automatic reads are coalesced; explicit user operations bypass this gate. No timers/wakeups. */
internal class BatteryRefreshGate {
    private var lastMac: String? = null
    private var lastAttempt: Long? = null

    fun tryAcquire(mac: String, now: Long): Boolean {
        val previous = lastAttempt
        if (mac.equals(lastMac, ignoreCase = true) && previous != null &&
            now >= previous && now - previous < 30_000L) return false
        lastMac = mac
        lastAttempt = now
        return true
    }
}
