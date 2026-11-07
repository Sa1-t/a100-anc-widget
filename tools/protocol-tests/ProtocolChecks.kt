package com.alpha.ancwidget

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun hex(value: String) = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private val battery = hex("FF0300090B9E01030100000062644E0000")
private val version = hex("FF030004000A830000030301")
private enum class BatteryTiming { ON_CONNECT, AFTER_SWITCH, AT_HANDSHAKE, NONE }
private enum class Reply { VALID, WRONG_MODE, WRONG_COMMAND, WRONG_VENDOR, WRONG_LENGTH, SILENT, WRONG_THEN_VALID }

/** Fragmented streams and independent ACKs exercise the production client, controller and cache. */
private class Device(
    val timing: BatteryTiming = BatteryTiming.ON_CONNECT,
    val reply: Reply = Reply.WRONG_THEN_VALID,
    val requireHandshake: Boolean = false,
    val versionReply: Boolean = true,
    val wrongVersion: Boolean = false,
    val connectFailure: Boolean = false,
    val batteryFrame: ByteArray = battery
) : BluetoothSocket() {
    private val incoming = ConcurrentLinkedQueue<Int>()
    val writes = mutableListOf<ByteArray>()
    val writeTimes = mutableListOf<Long>()
    var closed = false
    private var handshaken = false
    fun enqueue(frame: ByteArray) { frame.forEach { incoming.add(it.toInt() and 255) } }
    override val inputStream = object : InputStream() {
        override fun available() = incoming.size
        override fun read() = incoming.poll() ?: -1
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val count = minOf(len, 3, incoming.size)
            repeat(count) { buf[off + it] = read().toByte() }
            return count
        }
    }
    override val outputStream = object : OutputStream() {
        override fun write(value: Int) = error("Writes should be complete protocol frames")
        override fun write(buf: ByteArray, off: Int, len: Int) {
            val sent = buf.copyOfRange(off, off + len)
            writes.add(sent)
            writeTimes.add(System.nanoTime() / 1_000_000)
            if (sent.contentEquals(hex("FF010000000A0300"))) {
                check(writes.size == 2) { "Handshake must only follow one timed-out direct SWITCH" }
                if (timing == BatteryTiming.AT_HANDSHAKE) enqueue(batteryFrame)
                if (versionReply) {
                    enqueue(if (wrongVersion) version.copyOf().apply { this[11] = 2 } else version)
                    handshaken = true
                }
            } else {
                check(sent.size == 9 && sent.copyOfRange(0, 8).contentEquals(hex("FF0300010B9E000E")))
                check(sent[8].toInt() in 0..2) { "Only explicit SWITCH actions may be sent" }
                if (timing == BatteryTiming.AFTER_SWITCH) enqueue(batteryFrame)
                if (requireHandshake && !handshaken) return
                val action = sent[8].toInt()
                val valid = hex("FF0300010B9E010E%02X".format(action))
                val wrongMode = valid.copyOf().apply { this[8] = ((action + 1) % 3).toByte() }
                when (reply) {
                    Reply.VALID -> enqueue(valid)
                    Reply.WRONG_MODE -> enqueue(wrongMode)
                    Reply.WRONG_COMMAND -> enqueue(valid.copyOf().apply { this[7] = 0x0F })
                    Reply.WRONG_VENDOR -> enqueue(valid.copyOf().apply { this[5] = 0 })
                    Reply.WRONG_LENGTH -> enqueue(valid.copyOf(10).apply { this[3] = 2 })
                    Reply.SILENT -> Unit
                    Reply.WRONG_THEN_VALID -> { enqueue(wrongMode); enqueue(valid) }
                }
            }
        }
    }
    override fun connect() {
        if (connectFailure) throw IOException("not nearby")
        if (timing == BatteryTiming.ON_CONNECT) enqueue(batteryFrame)
    }
    override fun close() { closed = true }
}

private fun attach(context: Context, device: Device): Device {
    context.service = BluetoothManager(BluetoothAdapter(device))
    context.prefs.edit().putString(MainActivity.KEY_MAC, "00:11:22:33:44:55").apply()
    return device
}

fun main() {
    val parsed = checkNotNull(BatteryState.parse(battery))
    check(listOf(parsed.left, parsed.right, parsed.caseLevel) == listOf(98, 100, 78))
    check(parsed.leftCharging == BatteryState.ChargingState.NOT_CHARGING)
    check(!parsed.leftInCase && !parsed.rightInCase)
    val charged = checkNotNull(BatteryState.parse(battery.copyOf().apply {
        this[9] = 1; this[10] = 2; this[11] = 0xFF.toByte(); this[15] = 1
        this[12] = 0; this[13] = 0xFF.toByte(); this[14] = 101
    }))
    check(charged.left == 0 && charged.right == null && charged.caseLevel == null)
    check(charged.leftCharging == BatteryState.ChargingState.CHARGING)
    check(charged.rightCharging == BatteryState.ChargingState.MIS_CHARGING)
    check(charged.caseCharging == BatteryState.ChargingState.UNKNOWN && charged.leftInCase)
    check(BatteryState.display(null) == "--")
    for (index in 0..7) {
        check(BatteryState.parse(battery.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }) == null)
    }
    check(BatteryState.parse(battery.copyOf(16)) == null)
    check(BatteryState.parse(battery + 0) == null)
    println("PASS battery mapping, charging, unknown percentages and header/length rejection")

    GaiaClient.checkBytes()
    val context = Context()
    check(BatteryStore.read(context).left == null)
    check(BatteryStore.read(context).updatedAt == 0L)
    for ((mode, timing) in listOf(
        AmbientMode.ANC to BatteryTiming.ON_CONNECT,
        AmbientMode.TRANSPARENCY to BatteryTiming.AFTER_SWITCH,
        AmbientMode.NORMAL to BatteryTiming.ON_CONNECT
    )) {
        val device = attach(context, Device(timing))
        val result = AncModeController.changeMode(context)
        check(result == GaiaClient.Result.Success(mode))
        check(device.writes.size == 1)
        check(device.writes.single().contentEquals(hex("FF0300010B9E000E%02X".format(mode.value))))
        check(AncModeController.lastConfirmedMode(context) == mode)
        check(BatteryStore.read(context).left == 98)
        check(device.closed)
    }
    println("PASS three byte-exact direct SWITCH actions, no handshake, ACK-only ANC -> transparency -> normal cycle, fragmented broadcasts/ACKs")

    // Only one SPP endpoint exists. The other ear may be in its case, offline,
    // or have no readable battery at all; none of those may gate a SWITCH.
    val singleContext = Context()
    for (leftOnly in listOf(true, false)) for (otherInCase in listOf(true, false)) {
        val frame = battery.copyOf().apply {
            this[if (leftOnly) 13 else 12] = 0xFF.toByte()
            this[if (leftOnly) 16 else 15] = if (otherInCase) 1 else 0
        }
        for (mode in AmbientMode.values()) {
            val device = attach(singleContext, Device(reply = Reply.VALID, batteryFrame = frame))
            check(AncModeController.changeMode(singleContext, mode) == GaiaClient.Result.Success(mode))
            check(device.writes.single().contentEquals(hex("FF0300010B9E000E%02X".format(mode.value))))
            val snapshot = BatteryStore.read(singleContext)
            check(if (leftOnly) snapshot.right == null else snapshot.left == null)
            check(AncModeController.lastConfirmedMode(singleContext) == mode && device.closed)
        }
    }
    println("PASS single left/right ear x other in case/offline x all 3 modes: same direct SWITCH, matching ACK, one socket, CONNECT only")

    for ((level, color) in listOf(null to WidgetAppearance.NEUTRAL,
        -1 to WidgetAppearance.NEUTRAL, 101 to WidgetAppearance.NEUTRAL,
        0 to WidgetAppearance.RED, 19 to WidgetAppearance.RED,
        20 to WidgetAppearance.AMBER, 49 to WidgetAppearance.AMBER,
        50 to WidgetAppearance.GREEN, 100 to WidgetAppearance.GREEN)) {
        check(WidgetAppearance.batteryColor(level) == color)
    }
    check(WidgetAppearance.batteryColor(59, greenMin = 60, amberMin = 25) == WidgetAppearance.AMBER)
    check(AmbientMode.values().map(WidgetAppearance::modeColor).distinct().size == 3)
    println("PASS independent battery colors at 0/19/20/49/50/100/unknown boundaries, adjustable thresholds, 3 distinct mode colors")

    val disabledContext = Context()
    val toggleDisabledDevice = attach(disabledContext, Device(reply = Reply.VALID))
    disabledContext.prefs.edit().putInt("last_set_mode", AmbientMode.ANC.value).apply()
    WidgetSettings.setModeSwitchEnabled(disabledContext, false)
    for (target in listOf(null, AmbientMode.NORMAL, AmbientMode.TRANSPARENCY)) {
        check(AncModeController.changeMode(disabledContext, target) is GaiaClient.Result.Failure)
        check(toggleDisabledDevice.writes.isEmpty() && !toggleDisabledDevice.closed)
        check(AncModeController.lastConfirmedMode(disabledContext) == AmbientMode.ANC)
    }
    // Disabling taps must not disable background/broadcast battery reads.
    check(AncModeController.refreshBattery(disabledContext).received)
    check(toggleDisabledDevice.writes.isEmpty() && toggleDisabledDevice.closed)
    check(BatteryStore.read(disabledContext).left == 98)
    check(AncModeController.lastConfirmedMode(disabledContext) == AmbientMode.ANC)
    WidgetSettings.setModeSwitchEnabled(disabledContext, true)
    val enabledDevice = attach(disabledContext, Device(reply = Reply.VALID))
    check(AncModeController.changeMode(disabledContext) == GaiaClient.Result.Success(AmbientMode.TRANSPARENCY))
    check(enabledDevice.writes.single().contentEquals(hex("FF0300010B9E000E02")))
    println("PASS disabled mode switch sends no bytes or connection, retains confirmed mode and pure battery refresh; re-enable resumes ACK-only cycle")

    for (reply in listOf(Reply.WRONG_MODE, Reply.WRONG_COMMAND, Reply.WRONG_VENDOR, Reply.WRONG_LENGTH, Reply.SILENT)) {
        val device = attach(context, Device(reply = reply))
        check(AncModeController.changeMode(context) is GaiaClient.Result.Failure)
        check(AncModeController.lastConfirmedMode(context) == AmbientMode.NORMAL)
        check(device.closed && device.writes.size == 3)
        check(device.writes[0].contentEquals(device.writes[2]))
    }
    println("PASS wrong action/command/vendor/length ACK and both SWITCH timeouts never advance; exactly one retry")

    for (wrongVersion in listOf(false, true)) {
        val device = attach(context, Device(requireHandshake = true,
            versionReply = wrongVersion, wrongVersion = wrongVersion))
        check(AncModeController.changeMode(context) is GaiaClient.Result.Failure)
        check(device.writes.size == 2 && device.closed)
        check(AncModeController.lastConfirmedMode(context) == AmbientMode.NORMAL)
    }
    println("PASS missing or incorrect handshake reply prevents retry and never advances")

    val fallback = attach(context, Device(BatteryTiming.AT_HANDSHAKE, requireHandshake = true))
    check(AncModeController.changeMode(context) == GaiaClient.Result.Success(AmbientMode.ANC))
    check(fallback.writes.map { it.toList() } == listOf(
        hex("FF0300010B9E000E01").toList(), hex("FF010000000A0300").toList(), hex("FF0300010B9E000E01").toList()))
    check(fallback.writeTimes[1] - fallback.writeTimes[0] >= 1000)
    check(AncModeController.lastConfirmedMode(context) == AmbientMode.ANC && fallback.closed)
    check(BatteryStore.read(context).caseLevel == 78)
    println("PASS after 1s direct SWITCH timeout: unchanged handshake, exact version ACK, same SWITCH once, confirmed mode and battery saved")

    val refresh = attach(context, Device())
    check(AncModeController.refreshBattery(context).received)
    check(refresh.writes.isEmpty() && refresh.closed)
    check(AncModeController.lastConfirmedMode(context) == AmbientMode.ANC)
    val silentRefresh = attach(context, Device(BatteryTiming.NONE))
    check(!AncModeController.refreshBattery(context).received)
    check(silentRefresh.writes.isEmpty() && silentRefresh.closed)
    check(AncModeController.lastConfirmedMode(context) == AmbientMode.ANC)
    println("PASS refresh only listens, sends no handshake/SWITCH/SET, and success/timeout leave cycle position unchanged")

    val noBattery = attach(context, Device(BatteryTiming.NONE))
    check(AncModeController.changeMode(context, AmbientMode.TRANSPARENCY) == GaiaClient.Result.Success(AmbientMode.TRANSPARENCY))
    check(BatteryStore.read(context).left == 98)
    check(context.prefs.getInt("battery_left", -1) == 98) // missed reads keep last known values
    check(noBattery.closed)
    attach(context, Device(connectFailure = true))
    check(!AncModeController.refreshBattery(context).received)
    check(AncModeController.lastConfirmedMode(context) == AmbientMode.TRANSPARENCY)
    check(BatteryStore.read(context).left == 98)
    // A phase-3 failure may have hidden valid cache values. Upgrading restores them.
    context.prefs.edit().putBoolean("battery_available", false).apply()
    check(BatteryStore.read(context).left == 98)
    println("PASS absent broadcast/offline connection preserves last known battery and confirmed mode; phase-3 cache migrates")

    val neverRead = Context()
    val offline = attach(neverRead, Device(connectFailure = true))
    check(!AncModeController.refreshBattery(neverRead).received)
    check(BatteryStore.read(neverRead).left == null && offline.closed)
    val disabledDevice = attach(context, Device())
    (context.service as BluetoothManager).adapter!!.isEnabled = false
    check(!AncModeController.refreshBattery(context).received)
    check(disabledDevice.writes.isEmpty() && !disabledDevice.closed)
    check(BatteryStore.read(context).left == 98)
    println("PASS never-read shows --; Bluetooth off skips transport and preserves known values")

    val sizeCases = listOf(
        Triple(110f, 110f, WidgetLayoutPolicy.Layout.GRID), // legacy 2x2 minimum
        Triple(130f, 220f, WidgetLayoutPolicy.Layout.GRID), // documented Pixel 4 2x2 portrait
        Triple(269f, 117f, WidgetLayoutPolicy.Layout.GRID), // same host, landscape
        Triple(180f, 180f, WidgetLayoutPolicy.Layout.GRID), // reported bug: below old 220x300
        Triple(320f, 320f, WidgetLayoutPolicy.Layout.GRID),
        Triple(57f, 220f, WidgetLayoutPolicy.Layout.MEDIUM),
        Triple(276f, 102f, WidgetLayoutPolicy.Layout.WIDE),
        Triple(130f, 102f, WidgetLayoutPolicy.Layout.WIDE),
        Triple(109f, 109f, WidgetLayoutPolicy.Layout.COMPACT),
        Triple(48f, 48f, WidgetLayoutPolicy.Layout.COMPACT)
    )
    sizeCases.forEach { (width, height, expected) ->
        check(WidgetLayoutPolicy.select(width, height) == expected) { "$width x $height should use $expected" }
    }
    println("PASS size -> layout: legacy/portrait/landscape 2x2 and larger use grid; only smaller sizes collapse")

    android.appwidget.AppWidgetManager.widgetIds = intArrayOf(1, 2)
    BatteryRefreshScheduler.sync(context)
    BatteryRefreshScheduler.sync(context)
    val work = androidx.work.WorkManager.instance.requests
    check(work.size == 1 && work.values.single().intervalMs == 15 * 60 * 1000L)
    val worker = BatteryRefreshWorker(context, androidx.work.WorkerParameters())
    val workerDevice = attach(context, Device())
    val modeBeforeWorker = AncModeController.lastConfirmedMode(context)
    worker.doWork()
    check(workerDevice.closed && workerDevice.writes.isEmpty())
    check(AncModeController.lastConfirmedMode(context) == modeBeforeWorker)
    val manualDuringCooldown = attach(context, Device())
    check(AncModeController.refreshBattery(context).received)
    check(manualDuringCooldown.closed && manualDuringCooldown.writes.isEmpty())
    val workerOffline = attach(context, Device(connectFailure = true))
    worker.doWork()
    check(!workerOffline.closed) // A duplicate period does not reconnect within 30 seconds.
    android.os.SystemClock.elapsedOffset += 30_000L
    worker.doWork()
    check(workerOffline.closed && workerOffline.writes.isEmpty())
    check(BatteryStore.read(context).left == 98)
    android.appwidget.AppWidgetManager.widgetIds = intArrayOf()
    BatteryRefreshScheduler.sync(context)
    check(work.isEmpty())
    val noWidgetDevice = attach(context, Device())
    worker.doWork()
    check(!noWidgetDevice.closed)
    println("PASS Worker boundary: one 15-minute job across multiple widgets, zero command writes, offline cache retained, removal cancels")

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val exited = CountDownLatch(1)
    check(AncModeController.launch { entered.countDown(); release.await(); exited.countDown() })
    check(entered.await(1, TimeUnit.SECONDS))
    check(!AncModeController.launch { error("Concurrent operation accepted") })
    check(!AncModeController.runIfIdle { error("Background operation must skip busy transport") })
    android.appwidget.AppWidgetManager.widgetIds = intArrayOf(1)
    val busyDevice = attach(context, Device())
    BatteryRefreshWorker(context, androidx.work.WorkerParameters()).doWork()
    check(!busyDevice.closed && busyDevice.writes.isEmpty())
    release.countDown()
    check(exited.await(1, TimeUnit.SECONDS))
    println("PASS concurrent clicks are bounded instead of holding receivers in a queue")
    checkRefreshGate()
    checkBatteryMonitor()
    println("All protocol checks passed (fake transport/WorkManager boundary; no hardware or real scheduler claims).")
}

private fun awaitIdle() {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (!AncModeController.runIfIdle {}) {
        check(System.nanoTime() < deadline) { "Background refresh did not finish" }
        Thread.sleep(5)
    }
}

private fun checkBatteryMonitor() {
    awaitIdle()
    android.os.Build.VERSION.SDK_INT = 34
    android.appwidget.AppWidgetManager.widgetIds = intArrayOf(1)
    android.os.SystemClock.elapsedOffset += 30_000L
    val service = BatteryMonitorService()
    val initial = attach(service, Device())
    service.onCreate()
    awaitIdle()
    check(initial.closed && initial.writes.isEmpty())
    check(service.foregroundCalls == 1 && service.foregroundType == 16)
    check(service.receiverFlags == Context.RECEIVER_EXPORTED)
    check(service.registeredAction == "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
    check(service.onStartCommand(null, 0, 1) == android.app.Service.START_STICKY)
    check(!service.prefs.getBoolean("battery_system_fallback", false))
    val receiver = checkNotNull(service.registeredReceiver)
    fun event(level: Int, mac: String = "00:11:22:33:44:55") = android.content.Intent().apply {
        action = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        putExtra("android.bluetooth.device.extra.BATTERY_LEVEL", level)
        putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
            android.bluetooth.BluetoothAdapter(Device()).getRemoteDevice(mac))
    }
    val ignored = attach(service, Device(connectFailure = true))
    receiver.onReceive(service, event(91, "AA:BB:CC:DD:EE:FF"))
    receiver.onReceive(service, event(-1))
    receiver.onReceive(service, event(101))
    receiver.onReceive(service, event(91).apply { action = "other" })
    receiver.onReceive(service, android.content.Intent().apply {
        action = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        putExtra("android.bluetooth.device.extra.BATTERY_LEVEL", 91)
    })
    check(!ignored.closed && service.prefs.getInt("battery_system_level", -1) == -1)
    service.connectGranted = false
    receiver.onReceive(service, event(91))
    check(service.prefs.getInt("battery_system_level", -1) == -1)
    service.connectGranted = true
    receiver.onReceive(service, event(0))
    awaitIdle()
    check(!ignored.closed && service.prefs.getInt("battery_system_level", -1) == -1)
    android.os.SystemClock.elapsedOffset += 30_000L
    receiver.onReceive(service, event(0))
    awaitIdle()
    val fallback = BatteryStore.read(service)
    check(ignored.closed && ignored.writes.isEmpty())
    check(fallback.left == 98 && fallback.right == 100 && fallback.caseLevel == 78)
    check(service.prefs.getInt("battery_system_level", -1) == -1 && service.prefs.getLong("battery_system_updated", 0L) == 0L)
    check(AncModeController.lastConfirmedMode(service) == null)
    println("PASS API34 connectedDevice foreground + exported dynamic receiver; initial zero-command read; wrong MAC/action/missing device/invalid level/permission ignored; failed GAIA retains triples; aggregate level/timestamp not stored")

    val success = attach(service, Device())
    android.os.SystemClock.elapsedOffset += 30_000L
    receiver.onReceive(service, event(91))
    awaitIdle()
    check(success.closed && success.writes.isEmpty())
    check(service.prefs.getInt("battery_system_level", -1) == -1)
    check(!service.prefs.getBoolean("battery_system_fallback", false))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    check(AncModeController.launch { entered.countDown(); release.await() })
    check(entered.await(1, TimeUnit.SECONDS))
    val busy = attach(service, Device())
    receiver.onReceive(service, event(90))
    check(!busy.closed && service.prefs.getInt("battery_system_level", -1) == -1)
    release.countDown()
    awaitIdle()
    check(!busy.closed) // No queued retry after the lock is released.
    service.onDestroy()
    check(service.registeredReceiver == null && service.foregroundRemoved)
    BatteryStore.clear(service)
    check(service.prefs.getInt("battery_system_level", -1) == -1)
    check(BatteryStore.read(service).left == null)
    println("PASS successful GAIA retains triples without aggregate storage; busy transport skips without queuing/retrying; destroy unregisters receiver and removes notification; new selection clears battery cache")

    val denied = BatteryMonitorService().apply { connectGranted = false }
    attach(denied, Device())
    denied.onCreate()
    check(denied.stopped && denied.foregroundCalls == 0 && denied.registeredReceiver == null)
    BatteryMonitorService.sync(denied)
    check(denied.startedServices == 0 && denied.stoppedServices == 1)
    val noWidget = BatteryMonitorService()
    attach(noWidget, Device())
    android.appwidget.AppWidgetManager.widgetIds = intArrayOf()
    noWidget.onCreate()
    BatteryMonitorService.sync(noWidget)
    check(noWidget.stopped && noWidget.foregroundCalls == 0 && noWidget.startedServices == 0)
    check(noWidget.stoppedServices == 1)
    android.appwidget.AppWidgetManager.widgetIds = intArrayOf(1)
    val blocked = Context().apply { rejectStart = true }
    attach(blocked, Device())
    BatteryRefreshScheduler.sync(blocked)
    check(blocked.startedServices == 1 && androidx.work.WorkManager.instance.requests.size == 1)
    val restored = Context()
    attach(restored, Device())
    BatteryMonitorRestartReceiver().onReceive(restored, android.content.Intent().apply {
        action = android.content.Intent.ACTION_BOOT_COMPLETED
    })
    check(restored.startedServices == 1)
    android.appwidget.AppWidgetManager.widgetIds = intArrayOf()
    BatteryRefreshScheduler.sync(restored)
    check(restored.stoppedServices == 1 && androidx.work.WorkManager.instance.requests.isEmpty())
    println("PASS no CONNECT/widgets prevents foreground start; background start denial preserves Worker; boot restores monitor; last widget removal stops service and cancels work")

    android.os.Build.VERSION.SDK_INT = 26
    android.appwidget.AppWidgetManager.widgetIds = intArrayOf(1)
    android.os.SystemClock.elapsedOffset += 30_000L
    val legacy = BatteryMonitorService().apply { connectGranted = false }
    val legacyDevice = attach(legacy, Device())
    legacy.onCreate()
    awaitIdle()
    check(legacy.foregroundCalls == 1 && legacy.foregroundType == null && legacy.receiverFlags == null)
    check(legacyDevice.closed && legacyDevice.writes.isEmpty())
    attach(legacy, Device(connectFailure = true))
    legacy.registeredReceiver!!.onReceive(legacy, event(100))
    awaitIdle()
    check(legacy.prefs.getInt("battery_system_level", -1) == -1 && BatteryStore.read(legacy).left == 98)
    legacy.onDestroy()
    android.os.Build.VERSION.SDK_INT = 34
    println("PASS API26 notification/receiver legacy overloads, no runtime CONNECT requirement, legacy EXTRA_DEVICE read; 100% trigger never becomes a display value")
}

private fun checkRefreshGate() {
    val gate = BatteryRefreshGate()
    check(gate.tryAcquire("A", 0))
    check(!gate.tryAcquire("a", 29_999))
    check(gate.tryAcquire("A", 30_000))
    check(gate.tryAcquire("B", 30_001))
    check(gate.tryAcquire("B", 0)) // Clock reset cannot suppress forever.
    check(!gate.tryAcquire("B", 1))
    println("PASS background read coalescing: 30-second boundary, MAC identity and clock reset; broadcasts preserve GAIA cache during cooldown")
}
