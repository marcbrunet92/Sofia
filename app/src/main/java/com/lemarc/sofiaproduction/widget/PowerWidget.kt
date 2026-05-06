package com.lemarc.sofiaproduction.widget

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
import android.util.Log
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lemarc.sofiaproduction.MainActivity
import com.lemarc.sofiaproduction.R
import com.lemarc.sofiaproduction.data.SofiaRepository
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import androidx.core.graphics.toColorInt
import androidx.core.graphics.createBitmap

private const val TAG = "SofiaWidget"

// ─────────────────────────────────────────────
// AppWidgetProvider
// ─────────────────────────────────────────────

class SofiaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Affiche "Updating…" sur tous les widgets immédiatement
        for (id in appWidgetIds) {
            try {
                updateWidget(context, appWidgetManager, id)
            } catch (e: Exception) {
                Log.e(TAG, "onUpdate: failed to set placeholder for widget $id", e)
            }
        }

        try {
            scheduleWidgetWorker(context)
        } catch (e: Exception) {
            Log.e(TAG, "onUpdate: failed to schedule periodic worker", e)
        }

        try {
            triggerImmediateRefresh(context)
        } catch (e: Exception) {
            Log.e(TAG, "onUpdate: failed to trigger immediate refresh", e)
        }
    }

    override fun onEnabled(context: Context) {
        try {
            scheduleWidgetWorker(context)
        } catch (e: Exception) {
            Log.e(TAG, "onEnabled: failed to schedule worker", e)
        }
    }

    override fun onDisabled(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORKER_TAG)
        } catch (e: Exception) {
            Log.e(TAG, "onDisabled: failed to cancel worker", e)
        }
    }

    companion object {
        const val WORKER_TAG = "sofia_widget_refresh"

        private const val RING_SIZE_PX = 240
        private const val TOTAL_MW = 1_400.0

        private fun triggerImmediateRefresh(context: Context) {
            val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            mw: Double? = null,
            status: String? = null,
        ) {
            try {
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

                views.setImageViewBitmap(R.id.iv_widget_ring, createRingBitmap(percent))

                if (mw != null) {
                    views.setTextViewText(R.id.tv_widget_percent, "$percent%")
                    views.setTextViewText(R.id.tv_widget_mw, "${mw.toInt()} MW")
                    views.setTextViewText(R.id.tv_widget_status, status ?: "—")
                } else {
                    views.setTextViewText(R.id.tv_widget_percent, "—")
                    views.setTextViewText(R.id.tv_widget_mw, "— MW")
                    views.setTextViewText(R.id.tv_widget_status, "Updating…")
                }

                manager.updateAppWidget(widgetId, views)

            } catch (e: Exception) {
                Log.e(TAG, "updateWidget: failed for widgetId=$widgetId", e)
            }
        }

        private fun createRingBitmap(percent: Int): Bitmap {
            val size = RING_SIZE_PX
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            val strokeWidth = size * 0.12f
            val inset = strokeWidth / 2f
            val oval = RectF(inset, inset, size - inset, size - inset)

            paint.strokeWidth = strokeWidth
            paint.color = Color.argb(48, 255, 255, 255)
            canvas.drawArc(oval, -90f, 360f, false, paint)

            if (percent > 0) {
                paint.color = "#00D4FF".toColorInt()
                val sweep = 360f * percent / 100f
                canvas.drawArc(oval, -90f, sweep, false, paint)
            }

            return bitmap
        }

        fun forceRefresh(context: Context) {
            try {
                scheduleWidgetWorker(context)
                triggerImmediateRefresh(context)
            } catch (e: Exception) {
                Log.e(TAG, "forceRefresh: failed", e)
            }
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
        Log.d(TAG, "doWork: starting widget refresh")

        val snapshot = try {
            val repo = SofiaRepository()
            runBlocking { repo.fetchSnapshot() }.getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "doWork: fetchSnapshot threw an exception", e)
            null
        }

        if (snapshot == null) {
            Log.w(TAG, "doWork: snapshot is null, scheduling retry")
            return Result.retry()
        }

        return try {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SofiaWidgetProvider::class.java)
            )

            if (ids.isEmpty()) {
                Log.d(TAG, "doWork: no widget instances found, nothing to update")
                return Result.success()
            }

            for (id in ids) {
                SofiaWidgetProvider.updateWidget(
                    context  = context,
                    manager  = manager,
                    widgetId = id,
                    mw       = snapshot.latestMW,
                    status   = snapshot.statusLabel,
                )
            }

            Log.d(TAG, "doWork: updated ${ids.size} widget(s) successfully")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to update widget views", e)
            Result.retry()
        }
    }
}