package com.alpha.ancwidget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Bitmap artwork shared by the activity and RemoteViews; no custom widget View is needed. */
object BatteryRingRenderer {
    enum class Device { LEFT, RIGHT, CASE, MODE, SYSTEM }

    private data class Key(val device: Device, val level: Int?, val size: Int,
                           val mode: AmbientMode, val color: Int)
    // 24 x 160 x 160 x 4 = about 2.4 MiB at the widget's default size.
    // Never recycle evicted bitmaps: existing RemoteViews may still reference them.
    private val cache = object : LinkedHashMap<Key, Bitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>): Boolean = size > 24
    }

    // Widget bitmaps stay small and are shared across responsive variants in one update.
    @Synchronized
    fun render(device: Device, level: Int? = null, size: Int = 160,
               mode: AmbientMode = AmbientMode.NORMAL,
               palette: WidgetAppearance.Palette = WidgetAppearance.Palette()): Bitmap {
        val key = Key(device, if (device == Device.MODE) null else level?.takeIf { it in 0..100 },
            size, if (device == Device.MODE) mode else AmbientMode.NORMAL,
            if (device == Device.MODE) WidgetAppearance.modeColor(mode, palette)
            else WidgetAppearance.batteryColor(level, palette = palette))
        cache[key]?.let { return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / 100f, size / 100f)
        val modeColor = WidgetAppearance.modeColor(mode, palette)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (device == Device.MODE) 5f else 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = when {
                device == Device.MODE -> modeColor
                level == null || level !in 0..100 -> WidgetAppearance.NEUTRAL
                level == 0 -> palette.low
                else -> WidgetAppearance.TRACK
            }
        }
        val ring = RectF(8f, 8f, 92f, 92f)
        canvas.drawOval(ring, paint)
        if (device != Device.MODE && level != null && level in 1..100) {
            paint.color = WidgetAppearance.batteryColor(level, palette = palette)
            if (level == 100) canvas.drawOval(ring, paint)
            else canvas.drawArc(ring, -90f, level * 3.6f, false, paint)
        }
        when (device) {
            Device.LEFT, Device.RIGHT -> {
                paint.style = Paint.Style.STROKE
                paint.color = WidgetAppearance.ICON
                paint.strokeWidth = 3f
                canvas.save()
                if (device == Device.RIGHT) canvas.scale(-1f, 1f, 50f, 50f)
                // Center the specified left outline before mirroring it for the right ear.
                canvas.translate(4f, 0f)
                val ear = Path().apply {
                    moveTo(44f, 30f); lineTo(55f, 30f); quadTo(61f, 30f, 61f, 36f)
                    lineTo(61f, 47f); quadTo(61f, 51f, 57f, 53f); lineTo(57f, 66f)
                    quadTo(57f, 70f, 53f, 70f); quadTo(49f, 70f, 49f, 66f)
                    lineTo(49f, 52f); lineTo(44f, 52f); quadTo(39f, 52f, 39f, 47f)
                    lineTo(34f, 47f); quadTo(31f, 47f, 31f, 43f); lineTo(31f, 39f)
                    quadTo(31f, 35f, 35f, 35f); lineTo(39f, 35f)
                    quadTo(39f, 30f, 44f, 30f); close()
                }
                canvas.drawPath(ear, paint)
                canvas.restore()
            }
            Device.CASE -> {
                paint.style = Paint.Style.STROKE
                paint.color = WidgetAppearance.ICON
                paint.strokeWidth = 3f
                canvas.drawRoundRect(27f, 34f, 73f, 67f, 10f, 10f, paint)
                canvas.drawLine(28f, 46f, 72f, 46f, paint)
            }
            Device.SYSTEM -> {
                // Battery outline: the single number is the lower of the two earbuds.
                paint.style = Paint.Style.STROKE
                paint.color = WidgetAppearance.ICON
                paint.strokeWidth = 3f
                canvas.drawRoundRect(30f, 40f, 64f, 60f, 4f, 4f, paint)
                canvas.drawLine(64f, 46f, 70f, 46f, paint)
                canvas.drawLine(64f, 54f, 70f, 54f, paint)
                canvas.drawLine(70f, 46f, 70f, 54f, paint)
            }
            Device.MODE -> {
                paint.style = Paint.Style.STROKE
                paint.color = modeColor
                paint.strokeWidth = 3f
                when (mode) {
                    AmbientMode.NORMAL -> {
                        // Speaker: ordinary listening.
                        val speaker = Path().apply {
                            moveTo(30f, 43f); lineTo(40f, 43f); lineTo(53f, 33f)
                            lineTo(53f, 67f); lineTo(40f, 57f); lineTo(30f, 57f); close()
                        }
                        canvas.drawPath(speaker, paint)
                        canvas.drawArc(RectF(45f, 36f, 70f, 64f), -55f, 110f, false, paint)
                    }
                    AmbientMode.ANC -> {
                        // Shield with a slash: active noise cancellation.
                        val shield = Path().apply {
                            moveTo(50f, 28f); lineTo(70f, 36f); lineTo(67f, 56f)
                            quadTo(64f, 67f, 50f, 74f); quadTo(36f, 67f, 33f, 56f)
                            lineTo(30f, 36f); close()
                        }
                        canvas.drawPath(shield, paint)
                        canvas.drawLine(41f, 59f, 59f, 41f, paint)
                    }
                    AmbientMode.TRANSPARENCY -> {
                        // Open outward waves: sound from the surroundings.
                        canvas.drawLine(50f, 40f, 50f, 60f, paint)
                        canvas.drawArc(RectF(35f, 34f, 60f, 66f), 120f, 120f, false, paint)
                        canvas.drawArc(RectF(40f, 34f, 65f, 66f), -60f, 120f, false, paint)
                        canvas.drawArc(RectF(24f, 26f, 66f, 74f), 130f, 100f, false, paint)
                        canvas.drawArc(RectF(34f, 26f, 76f, 74f), -50f, 100f, false, paint)
                    }
                }
            }
        }
        cache[key] = bitmap
        return bitmap
    }
}
