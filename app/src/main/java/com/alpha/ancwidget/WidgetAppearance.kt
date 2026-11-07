package com.alpha.ancwidget

import kotlin.math.roundToInt

/** Presentation only: thresholds can be adjusted without changing battery/protocol state. */
object WidgetAppearance {
    const val GREEN_MIN = 50
    const val AMBER_MIN = 20
    // Keep these pure color values in sync with res/values/colors.xml.
    const val TRACK = 0xFF363C46.toInt()
    const val ICON = 0xFFE3E7ED.toInt()
    const val GREEN = 0xFF65CC88.toInt()
    const val AMBER = 0xFFE8BC60.toInt()
    const val RED = 0xFFEE7B82.toInt()
    const val NEUTRAL = 0xFF697381.toInt()

    data class Palette(
        val high: Int = GREEN, val medium: Int = AMBER, val low: Int = RED,
        val normal: Int = modeColor(AmbientMode.NORMAL),
        val anc: Int = modeColor(AmbientMode.ANC),
        val transparency: Int = modeColor(AmbientMode.TRANSPARENCY)
    )

    fun batteryColor(level: Int?, greenMin: Int = GREEN_MIN, amberMin: Int = AMBER_MIN,
                     palette: Palette = Palette()): Int {
        require(amberMin in 1..100 && greenMin in amberMin..100)
        return when {
            level == null || level !in 0..100 -> NEUTRAL
            level >= greenMin -> palette.high
            level >= amberMin -> palette.medium
            else -> palette.low
        }
    }

    fun modeColor(mode: AmbientMode): Int = when (mode) {
        AmbientMode.NORMAL -> 0xFFD3D9E2.toInt()
        AmbientMode.ANC -> 0xFF8DBBFA.toInt()
        AmbientMode.TRANSPARENCY -> 0xFF78D2BD.toInt()
    }

    fun modeColor(mode: AmbientMode, palette: Palette): Int = when (mode) {
        AmbientMode.NORMAL -> palette.normal
        AmbientMode.ANC -> palette.anc
        AmbientMode.TRANSPARENCY -> palette.transparency
    }
}

/** Ring-only geometry, calculated in pixels; fontScale consumes no space. */
internal object WidgetRingGeometry {
    data class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)
    data class Cell(val width: Int, val height: Int, val wrapper: Insets, val image: Insets)
    data class Geometry(val cardPadding: Int, val rows: List<Insets>, val cells: List<Cell>, val side: Int)

    // Match LinearLayout's sequential equal-weight allocation, including remainders.
    private fun split(length: Int, count: Int): List<Int> {
        var remaining = length
        return List(count) { index ->
            val share = remaining / (count - index)
            remaining -= share
            share
        }
    }

    private data class Axis(val cells: List<Int>, val before: List<Int>, val after: List<Int>)

    private fun axis(length: Int, count: Int, requestedGap: Int): Axis {
        val gap = if (count == 1) 0 else minOf(requestedGap, length / (count - 1))
        val wrappers = split(length, count)
        val cells = split(length - gap * (count - 1), count)
        val before = MutableList(count) { 0 }
        val after = MutableList(count) { 0 }
        for (index in 0 until count) {
            after[index] = wrappers[index] - before[index] - cells[index]
            if (index + 1 < count) before[index + 1] = gap - after[index]
        }
        return Axis(cells, before, after)
    }

    fun calculate(layout: WidgetLayoutPolicy.Layout, width: Float, height: Float, density: Float): Geometry {
        require(width >= 0 && height >= 0 && density.isFinite() && density > 0)
        fun px(dp: Float) = (dp * density).roundToInt()
        val standard = layout == WidgetLayoutPolicy.Layout.GRID && minOf(width, height) >= 160f
        val widthPx = px(width)
        val heightPx = px(height)
        val padding = minOf(px(if (standard) 12f else 6f), minOf(widthPx, heightPx) / 2)
        val gap = px(if (standard) 8f else 4f)
        val columns = when (layout) {
            WidgetLayoutPolicy.Layout.GRID -> 2
            WidgetLayoutPolicy.Layout.WIDE -> 2
            else -> 1
        }
        val rows = when (layout) {
            WidgetLayoutPolicy.Layout.GRID, WidgetLayoutPolicy.Layout.MEDIUM -> 2
            else -> 1
        }
        val horizontal = axis(widthPx - 2 * padding, columns, gap)
        val vertical = axis(heightPx - 2 * padding, rows, gap)
        val maxSide = when {
            standard -> 64f
            layout == WidgetLayoutPolicy.Layout.GRID -> 44f
            else -> 40f
        }
        // No labels, captions or image-to-text gap: the image slot is the entire cell.
        val side = minOf(kotlin.math.floor(maxSide * density).toInt(),
            horizontal.cells.min() - 2 * px(if (standard) 4f else 2f), vertical.cells.min()).coerceAtLeast(0)
        val rowPadding = if (layout == WidgetLayoutPolicy.Layout.GRID) List(rows) {
            Insets(top = vertical.before[it], bottom = vertical.after[it])
        } else emptyList()
        val cells = List(rows * columns) { index ->
            val column = index % columns
            val row = index / columns
            val cellWidth = horizontal.cells[column]
            val cellHeight = vertical.cells[row]
            val extraX = cellWidth - side
            val extraY = cellHeight - side
            Cell(cellWidth, cellHeight, Insets(
                left = horizontal.before[column], right = horizontal.after[column],
                top = if (layout == WidgetLayoutPolicy.Layout.MEDIUM) vertical.before[row] else 0,
                bottom = if (layout == WidgetLayoutPolicy.Layout.MEDIUM) vertical.after[row] else 0
            ), Insets(extraX / 2, extraY / 2, extraX - extraX / 2, extraY - extraY / 2))
        }
        return Geometry(padding, rowPadding, cells, side)
    }
}
