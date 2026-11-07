package com.alpha.ancwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import android.widget.Toast

/** Every resize rebuilds its views from the same persisted mode/battery snapshot. */
class AncWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_TOGGLE = "com.alpha.ancwidget.ACTION_TOGGLE"

        fun updateAll(context: Context, text: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AncWidgetProvider::class.java))
            val provider = AncWidgetProvider()
            for (id in ids) provider.updateWidget(context, manager, id, status = text)
        }
    }

    override fun onEnabled(context: Context) { BatteryRefreshScheduler.sync(context) }
    override fun onDisabled(context: Context) { BatteryRefreshScheduler.sync(context) }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
        BatteryRefreshScheduler.sync(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        if (!WidgetSettings.modeSwitchEnabled(context)) return
        val mac = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_MAC, null)
        if (mac.isNullOrEmpty()) {
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            return
        }
        val pending = goAsync()
        val started = AncModeController.launch {
            try {
                val result = AncModeController.changeMode(context)
                if (result is GaiaClient.Result.Failure) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "失敗：${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally { pending.finish() }
        }
        BatteryMonitorService.sync(context) // Claim the user operation before initial service refresh.
        if (!started) {
            pending.finish()
            Toast.makeText(context, "操作進行中，請稍候", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWidget(
        context: Context, manager: AppWidgetManager, id: Int,
        options: Bundle = manager.getAppWidgetOptions(id), status: String? = null
    ) {
        val battery = BatteryStore.read(context)
        val mode = AncModeController.lastConfirmedMode(context)
        // One persisted mode snapshot drives both Bitmap colors and every variant's background.
        val shownMode = mode ?: AmbientMode.NORMAL
        val palette = WidgetSettings.palette(context)
        val opacity = WidgetSettings.opacity(context)
        val switchEnabled = WidgetSettings.modeSwitchEnabled(context)
        val artwork = mapOf(
            BatteryRingRenderer.Device.LEFT to BatteryRingRenderer.render(BatteryRingRenderer.Device.LEFT, battery.left, palette = palette),
            BatteryRingRenderer.Device.RIGHT to BatteryRingRenderer.render(BatteryRingRenderer.Device.RIGHT, battery.right, palette = palette),
            BatteryRingRenderer.Device.CASE to BatteryRingRenderer.render(BatteryRingRenderer.Device.CASE, battery.caseLevel, palette = palette),
            BatteryRingRenderer.Device.SYSTEM to BatteryRingRenderer.render(BatteryRingRenderer.Device.SYSTEM, battery.systemLevel, palette = palette),
            BatteryRingRenderer.Device.MODE to BatteryRingRenderer.render(BatteryRingRenderer.Device.MODE,
                mode = shownMode, palette = palette)
        )
        fun build(width: Float, height: Float): RemoteViews {
            val layoutType = WidgetLayoutPolicy.select(width, height)
            val layout = when (layoutType) {
                WidgetLayoutPolicy.Layout.GRID -> R.layout.widget_anc_large
                WidgetLayoutPolicy.Layout.WIDE -> R.layout.widget_anc_wide
                WidgetLayoutPolicy.Layout.MEDIUM -> R.layout.widget_anc_medium
                WidgetLayoutPolicy.Layout.COMPACT -> R.layout.widget_anc
            }
            return createViews(context, layout, status, battery, shownMode, artwork, opacity, switchEnabled).also {
                applyGeometry(context, it, layoutType, width, height)
            }
        }
        // API 31+: use actual host sizes, never infer cell count from dp or let a
        // higher-area wide variant displace a fitting 2x2 grid. Some hosts omit SIZES.
        @Suppress("DEPRECATION")
        val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
                ?.distinct()
        } else null
        val views = if (!sizes.isNullOrEmpty() && sizes.size <= 16) {
            RemoteViews(sizes.associateWith { build(it.width, it.height) })
        } else {
            // API 26–30 and hosts without SIZES: width/height extrema are paired
            // by orientation, not MIN_WIDTH + MIN_HEIGHT (which may never coexist).
            fun dimension(key: String) = options.getInt(key, WidgetLayoutPolicy.GRID_MIN_DP)
                .takeIf { it > 0 }?.toFloat() ?: WidgetLayoutPolicy.GRID_MIN_DP.toFloat()
            val portrait = build(dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
            val landscape = build(dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
            RemoteViews(landscape, portrait)
        }
        manager.updateAppWidget(id, views)
    }

    private fun createViews(
        context: Context, layout: Int, status: String?, battery: BatteryStore.Snapshot,
        shownMode: AmbientMode, artwork: Map<BatteryRingRenderer.Device, Bitmap>,
        opacity: Int, switchEnabled: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        // Opaque RGB source + drawable alpha, independently of the fully opaque ring artwork.
        // Rounded shapes retain their dp corner radius at every size; no background Bitmap.
        views.setImageViewResource(R.id.card_background, if (layout == R.layout.widget_anc)
            R.drawable.widget_card_compact_opaque else R.drawable.widget_card_opaque)
        views.setInt(R.id.card_background, "setImageAlpha", WidgetSettings.opacityAlpha(opacity))
        val description = (status ?: AncModeController.widgetLabel(context)) +
            if (switchEnabled) "，點一下循環切換" else "，模式切換已關閉"
        views.setInt(R.id.mode_button, "setBackgroundResource", when (shownMode) {
            AmbientMode.NORMAL -> R.drawable.mode_normal
            AmbientMode.ANC -> R.drawable.mode_anc
            AmbientMode.TRANSPARENCY -> R.drawable.mode_transparency
        })
        // Replacing an InsetDrawable can restore its padding; it must not shrink this slot.
        views.setViewPadding(R.id.mode_button, 0, 0, 0, 0)
        fun bindContent(cell: Int, image: Int, content: WidgetSettings.Content) {
            val device = when (content) {
                WidgetSettings.Content.MODE -> BatteryRingRenderer.Device.MODE
                WidgetSettings.Content.LEFT -> BatteryRingRenderer.Device.LEFT
                WidgetSettings.Content.RIGHT -> BatteryRingRenderer.Device.RIGHT
                WidgetSettings.Content.CASE -> BatteryRingRenderer.Device.CASE
                WidgetSettings.Content.SYSTEM -> BatteryRingRenderer.Device.SYSTEM
            }
            val level = when (content) {
                WidgetSettings.Content.LEFT -> battery.left
                WidgetSettings.Content.RIGHT -> battery.right
                WidgetSettings.Content.CASE -> battery.caseLevel
                WidgetSettings.Content.SYSTEM -> battery.systemLevel
                WidgetSettings.Content.MODE -> null
            }
            views.setImageViewBitmap(image, artwork.getValue(device))
            views.setContentDescription(cell, if (content == WidgetSettings.Content.MODE) description
                else "${content.label} ${BatteryState.display(level)}，${battery.caption()}，$description")
            bindToggle(context, views, cell, switchEnabled)
        }
        // Slot IDs describe their original positions; the selected content controls artwork and accessibility.
        when (layout) {
            R.layout.widget_anc_large -> {
                bindContent(R.id.cell_left, R.id.ring_left, WidgetSettings.Content.LEFT)
                bindContent(R.id.cell_right, R.id.ring_right, WidgetSettings.Content.RIGHT)
                bindContent(R.id.cell_case, R.id.ring_case, WidgetSettings.Content.CASE)
                bindContent(R.id.mode_button, R.id.mode_ring, WidgetSettings.Content.MODE)
            }
            R.layout.widget_anc -> bindContent(R.id.mode_button, R.id.mode_ring,
                WidgetSettings.content(context, WidgetSettings.ContentSlot.COMPACT))
            else -> {
                val first = if (layout == R.layout.widget_anc_wide) WidgetSettings.ContentSlot.WIDE_FIRST
                    else WidgetSettings.ContentSlot.TALL_FIRST
                val second = if (layout == R.layout.widget_anc_wide) WidgetSettings.ContentSlot.WIDE_SECOND
                    else WidgetSettings.ContentSlot.TALL_SECOND
                bindContent(R.id.cell_case, R.id.ring_case, WidgetSettings.content(context, first))
                bindContent(R.id.mode_button, R.id.mode_ring, WidgetSettings.content(context, second))
            }
        }
        return views
    }

    private fun applyGeometry(
        context: Context, views: RemoteViews, layout: WidgetLayoutPolicy.Layout, width: Float, height: Float
    ) {
        val geometry = WidgetRingGeometry.calculate(layout, width, height, context.resources.displayMetrics.density)
        fun padding(id: Int, value: WidgetRingGeometry.Insets) {
            views.setViewPadding(id, value.left, value.top, value.right, value.bottom)
        }
        val p = geometry.cardPadding
        views.setViewPadding(R.id.widget_card, p, p, p, p)
        if (layout == WidgetLayoutPolicy.Layout.GRID) {
            padding(R.id.row_top, geometry.rows[0])
            padding(R.id.row_bottom, geometry.rows[1])
        }
        val images = when (layout) {
            WidgetLayoutPolicy.Layout.GRID ->
                listOf(R.id.ring_left, R.id.ring_right, R.id.ring_case, R.id.mode_ring)
            WidgetLayoutPolicy.Layout.MEDIUM, WidgetLayoutPolicy.Layout.WIDE -> listOf(R.id.ring_case, R.id.mode_ring)
            WidgetLayoutPolicy.Layout.COMPACT -> listOf(R.id.mode_ring)
        }
        val wrappers = when (layout) {
            WidgetLayoutPolicy.Layout.GRID ->
                listOf(R.id.wrap_left, R.id.wrap_right, R.id.wrap_case, R.id.wrap_mode)
            WidgetLayoutPolicy.Layout.MEDIUM, WidgetLayoutPolicy.Layout.WIDE -> listOf(R.id.wrap_case, R.id.wrap_mode)
            WidgetLayoutPolicy.Layout.COMPACT -> emptyList()
        }
        geometry.cells.forEachIndexed { index, cell ->
            if (wrappers.isNotEmpty()) padding(wrappers[index], cell.wrapper)
            padding(images[index], cell.image)
        }
    }

    private fun bindToggle(context: Context, views: RemoteViews, id: Int, enabled: Boolean) {
        if (!enabled) {
            views.setOnClickPendingIntent(id, null)
            return
        }
        val intent = Intent(context, AncWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
        views.setOnClickPendingIntent(id, PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
    }
}
