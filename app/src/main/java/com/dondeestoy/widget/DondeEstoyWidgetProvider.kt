package com.dondeestoy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

class DondeEstoyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WHERE_AM_I -> {
                updateStatus(context, "Ubicando…")
                val i = Intent(context, LocateActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_ACTION, ACTION_WHERE_AM_I)
                }
                context.startActivity(i)
            }
            ACTION_GO_HOME -> updateStatus(context, "Ruta a casa (pronto)")
            ACTION_EMERGENCY -> updateStatus(context, "Buscando ayuda cercana…")
        }
    }

    companion object {
        const val ACTION_WHERE_AM_I = "com.dondeestoy.widget.ACTION_WHERE_AM_I"
        const val ACTION_GO_HOME = "com.dondeestoy.widget.ACTION_GO_HOME"
        const val ACTION_EMERGENCY = "com.dondeestoy.widget.ACTION_EMERGENCY"
        const val EXTRA_ACTION = "extra_action"

        fun updateStatus(context: Context, status: String, city: String? = null) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, DondeEstoyWidgetProvider::class.java))
            val views = buildViews(context).apply {
                setTextViewText(R.id.text_status, status)
                if (city != null) {
                    setTextViewText(R.id.text_city, city)
                    setViewVisibility(R.id.text_city, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.text_city, View.GONE)
                }
            }
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_donde_estoy)
            views.setOnClickPendingIntent(R.id.btn_where, pendingIntent(context, ACTION_WHERE_AM_I, 1))
            views.setOnClickPendingIntent(R.id.btn_home, pendingIntent(context, ACTION_GO_HOME, 2))
            views.setOnClickPendingIntent(R.id.btn_emergency, pendingIntent(context, ACTION_EMERGENCY, 3))
            views.setOnClickPendingIntent(R.id.image_map, pendingIntent(context, ACTION_WHERE_AM_I, 4))
            views.setOnClickPendingIntent(R.id.map_container, pendingIntent(context, ACTION_WHERE_AM_I, 5))
            return views
        }

        private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, DondeEstoyWidgetProvider::class.java).apply { this.action = action }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }
}
