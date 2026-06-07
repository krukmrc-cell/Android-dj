package com.krukkex.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "krukkex_audio"
        const val NOTIFICATION_ID = 1
    }

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var currentUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        player = ExoPlayer.Builder(this).build()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?: Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        // Direct als foreground starten zodat Android het proces niet opruimt
        startForeground(NOTIFICATION_ID, buildNotification("Klaar"))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY" -> {
                val url = intent.getStringExtra("url") ?: return START_STICKY
                currentUrl = url
                playFresh(url)
                updateNotification("Speelt af")
            }
            "PAUSE"  -> { player.pause(); updateNotification("Gepauzeerd") }
            "RESUME" -> {
                // Niet hervaten vanuit buffer — altijd opnieuw verbinden voor live positie
                currentUrl?.let { playFresh(it) }
                updateNotification("Speelt af")
            }
            "STOP"   -> { player.stop(); currentUrl = null; updateNotification("Gestopt") }
            "VOLUME" -> player.volume = intent.getFloatExtra("volume", 1f).coerceIn(0f, 1f)
        }
        return START_STICKY
    }

    private fun playFresh(url: String) {
        player.stop()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "KrukkexRadio", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KrukkexRadio")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        mediaSession?.release()
        player.release()
        super.onDestroy()
    }
}
