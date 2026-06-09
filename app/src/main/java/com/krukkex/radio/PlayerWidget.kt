package com.krukkex.radio

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * Donkere mini audioplayer-widget (de basisvariant), Poweramp-stijl: artwork + titel/artiest
 * + één paarse play/pause-knop. Alle logica zit in [WidgetRenderer]; de witte variant is
 * [PlayerWidgetLight].
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetRenderer.renderProvider(
            context, appWidgetManager, appWidgetIds,
            PlayerWidget::class.java
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRenderer.ACTION_TOGGLE) {
            WidgetRenderer.togglePlayback(context, goAsync())
        }
    }
}
