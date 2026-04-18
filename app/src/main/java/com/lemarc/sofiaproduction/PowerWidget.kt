package com.lemarc.sofiaproduction

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────
// AppWidgetProvider
// ─────────────────────────────────────────────

class SofiaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
        scheduleWidgetWorker(context)
    }

    override fun onEnabled(context: Context) {
        scheduleWidgetWorker(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORKER_TAG)
    }

    companion object {
        const val WORKER_TAG = "sofia_widget_refresh"

        private const val RING_SIZE_PX = 240
        private const val TOTAL_MW = 1_400.0

        fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            mw: Double? = null,
            cf: Int? = null,
            status: String? = null,
            source: String? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.power_widget)

            // Tap to open app
            val launchIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            val percent = if (mw != null) {
                ((mw / TOTAL_MW) * 100).toInt().coerceIn(0, 100)
            } else 0

            // Circular progress ring
            views.setImageViewBitmap(R.id.iv_widget_ring, createRingBitmap(percent))

            if (mw != null) {
                views.setTextViewText(R.id.tv_widget_percent, "$percent%")
                views.setTextViewText(R.id.tv_widget_mw, "${mw.toInt()} MW")
                views.setTextViewText(R.id.tv_widget_status, status ?: "—")
                views.setTextViewText(
                    R.id.tv_widget_source,
                    if (source == "b1610") "Metered" else "Forecast"
                )
            } else {
                views.setTextViewText(R.id.tv_widget_percent, "—")
                views.setTextViewText(R.id.tv_widget_mw, "— MW")
                views.setTextViewText(R.id.tv_widget_status, "Updating…")
                views.setTextViewText(R.id.tv_widget_source, "")
            }

            manager.updateAppWidget(widgetId, views)
        }

        /**
         * Draws a circular arc ring: a dim full-circle track and a cyan progress arc.
         * The arc starts at the top (−90°) and sweeps clockwise by [percent]/100 × 360°.
         */
        private fun createRingBitmap(percent: Int): Bitmap {
            val size = RING_SIZE_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            val strokeWidth = size * 0.12f
            val inset = strokeWidth / 2f
            val oval = RectF(inset, inset, size - inset, size - inset)

            // Track (dim background ring)
            paint.strokeWidth = strokeWidth
            paint.color = Color.argb(48, 255, 255, 255)
            canvas.drawArc(oval, -90f, 360f, false, paint)

            // Progress arc (cyan)
            if (percent > 0) {
                paint.color = Color.parseColor("#00D4FF")
                val sweep = 360f * percent / 100f
                canvas.drawArc(oval, -90f, sweep, false, paint)
            }

            return bitmap
        }
        fun forceRefresh(context: Context) {
            scheduleWidgetWorker(context)
        }
        private fun scheduleWidgetWorker(context: Context) {
            val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORKER_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}

// ─────────────────────────────────────────────
// WorkManager worker — runs every 30 min
// ─────────────────────────────────────────────

class WidgetRefreshWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val repo = SofiaRepository()
        val snapshot = runBlocking { repo.fetchSnapshot() }.getOrNull() ?: return Result.retry()

        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, SofiaWidgetProvider::class.java)
        )

        for (id in ids) {
            SofiaWidgetProvider.updateWidget(
                context  = context,
                manager  = manager,
                widgetId = id,
                mw       = snapshot.latestMW,
                cf       = (snapshot.capacityFactor * 100).toInt(),
                status   = snapshot.statusLabel,
                source   = snapshot.source
            )
        }

        return Result.success()
    }
}