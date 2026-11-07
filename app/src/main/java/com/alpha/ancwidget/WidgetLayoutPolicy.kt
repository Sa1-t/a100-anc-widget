package com.alpha.ancwidget

/** Size is the usable dp reported by the host, not a launcher cell count. */
object WidgetLayoutPolicy {
    enum class Layout { COMPACT, MEDIUM, WIDE, GRID }

    // 110dp is Android's legacy two-cell minimum (70 * 2 - 30). Modern hosts
    // use targetCellWidth/Height=2; their actual SizeF values remain authoritative.
    // Ring-only compact GRID: 12dp card padding + 4dp gap + 2 * 47dp cells.
    // Each cell centers a Bitmap up to 44dp, with 2dp horizontal safety on each side.
    const val GRID_MIN_DP = 110
    const val WIDE_MIN_WIDTH_DP = 110
    const val WIDE_MIN_HEIGHT_DP = 48

    fun select(width: Float, height: Float): Layout = when {
        width >= GRID_MIN_DP && height >= GRID_MIN_DP -> Layout.GRID
        width >= WIDE_MIN_WIDTH_DP && height >= WIDE_MIN_HEIGHT_DP -> Layout.WIDE
        height >= GRID_MIN_DP -> Layout.MEDIUM
        else -> Layout.COMPACT
    }
}
