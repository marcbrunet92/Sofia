package com.lemarc.sofiaproduction

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

            if (mw != null) {
                views.setTextViewText(R.id.tv_widget_mw, "${mw.toInt()} MW")
                views.setTextViewText(R.id.tv_widget_cf, "${cf ?: 0}% CF")
                views.setTextViewText(R.id.tv_widget_status, status ?: "—")
                views.setTextViewText(
                    R.id.tv_widget_source,
                    if (source == "b1610") "Metered" else "Forecast"
                )
            } else {
                views.setTextViewText(R.id.tv_widget_mw, "— MW")
                views.setTextViewText(R.id.tv_widget_cf, "")
                views.setTextViewText(R.id.tv_widget_status, "Updating…")
                views.setTextViewText(R.id.tv_widget_source, "")
            }

            manager.updateAppWidget(widgetId, views)
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