package com.krukkex.radio

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * Witte variant van de mini audioplayer-widget (zelfde frontend-paarse accenten als de
 * donkere [PlayerWidget]). Alle logica zit in [WidgetRenderer].
 */
class PlayerWidgetLight : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetRenderer.renderProvider(
            context, appWidgetManager, appWidgetIds,
            PlayerWidgetLight::class.java
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRenderer.ACTION_TOGGLE) {
            WidgetRenderer.togglePlayback(context, goAsync())
        }
    }
}
