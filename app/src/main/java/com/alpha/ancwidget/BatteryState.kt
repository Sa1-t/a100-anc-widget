package com.alpha.ancwidget

/** Cambridge custom GET_BATTERY_STATE, confirmed in Connect 2.6.0 PacketDecode. */
data class BatteryState(
    val left: Int?,
    val right: Int?,
    val caseLevel: Int?,
    val leftCharging: ChargingState,
    val rightCharging: ChargingState,
    val caseCharging: ChargingState,
    val leftInCase: Boolean,
    val rightInCase: Boolean
) {
    enum class ChargingState(val wireValue: Int) {
        NOT_CHARGING(0), CHARGING(1), MIS_CHARGING(2), UNKNOWN(255);

        companion object {
            fun from(value: Int) = values().firstOrNull { it.wireValue == value } ?: UNKNOWN
        }
    }

    companion object {
        private val HEADER = byteArrayOf(
            0xFF.toByte(), 0x03, 0x00, 0x09, 0x0B, 0x9E.toByte(), 0x01, 0x03
        )

        fun parse(frame: ByteArray): BatteryState? {
            if (frame.size != 17 || !frame.copyOfRange(0, 8).contentEquals(HEADER)) return null
            fun byte(index: Int) = frame[8 + index].toInt() and 0xFF
            fun percent(index: Int) = byte(index).takeIf { it in 0..100 }
            return BatteryState(
                left = percent(4), right = percent(5), caseLevel = percent(6),
                leftCharging = ChargingState.from(byte(1)),
                rightCharging = ChargingState.from(byte(2)),
                caseCharging = ChargingState.from(byte(3)),
                leftInCase = byte(7) != 0, rightInCase = byte(8) != 0
            )
        }

        fun display(value: Int?): String = value?.let { "$it%" } ?: "--"
    }
}
