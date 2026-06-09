package com.krukkex.radio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Gedeelde render-/besturingslogica voor de startscherm-widgets. Twee kiesbare thema's met
 * dezelfde view-id's: [PlayerWidget] (zwart) en [PlayerWidgetLight] (wit). Beide gebruiken de
 * frontend-paarse accentkleur en zijn responsive: een compacte rij op klein formaat, en grote
 * gecentreerde albumart zodra je de widget op het startscherm groter sleept (Android 12+).
 *
 * De now-playing-status staat in SharedPreferences (overleeft service-dood). Play/pause stuurt
 * de bestaande [AudioService] aan via een kortstondige MediaController; bewust geen skip
 * (vereist de live WebView-socket).
 */
object WidgetRenderer {
    const val ACTION_TOGGLE = "com.krukkex.radio.widget.TOGGLE"
    private const val PREFS = "krukkex_widget"
    private val artExecutor = Executors.newSingleThreadExecutor()

    // Eenvoudige in-memory artwork-cache zodat re-renders (status- en groottewissels) niet
    // telkens opnieuw downloaden.
    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedBmp: Bitmap? = null
    @Volatile private var loadingUrl: String? = null

    private data class Variant(val provider: Class<*>, val compact: Int, val large: Int)

    private val variants = listOf(
        Variant(PlayerWidget::class.java, R.layout.widget_player, R.layout.widget_player_large),
        Variant(PlayerWidgetLight::class.java, R.layout.widget_player_light, R.layout.widget_player_large_light)
    )

    /** Door [AudioService] aangeroepen bij elke verandering van track of afspeelstatus. */
    fun pushState(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String,
        isPlaying: Boolean
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", title)
            .putString("artist", artist)
            .putString("artwork", artworkUrl)
            .putBoolean("playing", isPlaying)
            .apply()
        renderAll(context)
    }

    private fun renderAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        for (v in variants) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, v.provider))
            ids.forEach { renderWidget(context, mgr, it, v) }
        }
    }

    /** Door een provider aangeroepen vanuit onUpdate. */
    fun renderProvider(
        context: Context,
        mgr: AppWidgetManager,
        widgetIds: IntArray,
        providerClass: Class<*>
    ) {
        val v = variants.firstOrNull { it.provider == providerClass } ?: return
        widgetIds.forEach { renderWidget(context, mgr, it, v) }
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, widgetId: Int, v: Variant) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString("title", null)?.takeIf { it.isNotBlank() } ?: "KrukkeX Radio"
        val artist = prefs.getString("artist", null)?.takeIf { it.isNotBlank() } ?: "Live radio"
        val artworkUrl = prefs.getString("artwork", null) ?: ""
        val playing = prefs.getBoolean("playing", false)

        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Responsive: klein → compacte rij, groot → grote albumart-layout.
            RemoteViews(
                mapOf(
                    SizeF(180f, 72f) to buildViews(context, v.provider, v.compact, title, artist, artworkUrl, playing),
                    SizeF(180f, 170f) to buildViews(context, v.provider, v.large, title, artist, artworkUrl, playing)
                )
            )
        } else {
            buildViews(context, v.provider, v.compact, title, artist, artworkUrl, playing)
        }

        mgr.updateAppWidget(widgetId, views)

        // Artwork nog niet in cache → async ophalen en daarna opnieuw renderen (met cache).
        if (artworkUrl.startsWith("http") && artworkUrl != cachedUrl && artworkUrl != loadingUrl) {
            startArtLoad(context, artworkUrl)
        }
    }

    private fun buildViews(
        context: Context,
        providerClass: Class<*>,
        layoutId: Int,
        title: String,
        artist: String,
        artworkUrl: String,
        playing: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)
        views.setImageViewResource(
            R.id.widget_toggle,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        )

        val art = if (artworkUrl.isNotEmpty() && artworkUrl == cachedUrl) cachedBmp else null
        if (art != null) {
            views.setImageViewBitmap(R.id.widget_art, art)
        } else {
            views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
        }

        // Tik op de widget → open de app.
        val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

        // Play/pause-knop → toggle via de MediaSession (richt op de eigen provider).
        val toggleIntent = Intent(context, providerClass).apply { action = ACTION_TOGGLE }
        views.setOnClickPendingIntent(
            R.id.widget_toggle,
            PendingIntent.getBroadcast(
                context, 1, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

        return views
    }

    private fun startArtLoad(context: Context, url: String) {
        loadingUrl = url
        artExecutor.execute {
            val bmp = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }

            if (bmp != null) {
                // Schaal naar max 256px zodat we onder de RemoteViews-transactielimiet blijven.
                cachedBmp = scaleDown(bmp, 256)
                cachedUrl = url
            }
            loadingUrl = null
            if (bmp != null) renderAll(context) // opnieuw renderen, nu met cache-hit
        }
    }

    private fun scaleDown(src: Bitmap, max: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= max && h <= max) return src
        val ratio = minOf(max.toFloat() / w, max.toFloat() / h)
        return Bitmap.createScaledBitmap(src, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    /** Verbind kort met de MediaSession en wissel play/pause. */
    fun togglePlayback(context: Context, pending: android.content.BroadcastReceiver.PendingResult?) {
        val token = SessionToken(context, ComponentName(context, AudioService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            var controller: MediaController? = null
            try {
                controller = future.get()
                val c = controller!!
                // Pauzeren kan altijd; afspelen alleen als er een item klaarstaat
                // (de stream-URL komt uit de app — een koude start opent de app).
                if (c.isPlaying) c.pause() else if (c.mediaItemCount > 0) c.play()
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try { controller?.release() } catch (_: Exception) {}
                pending?.finish()
            }, 700)
        }, ContextCompat.getMainExecutor(context))
    }
}
