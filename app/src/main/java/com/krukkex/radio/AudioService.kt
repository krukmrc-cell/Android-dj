package com.krukkex.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URL

class AudioService : Service() {

    companion object {
        const val CHANNEL_ID = "krukkex_audio"
        const val NOTIFICATION_ID = 1
    }

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var currentUrl: String? = null
    private var isPlaying = false
    private var trackTitle: String = "KrukkexRadio"
    private var trackArtist: String = ""
    private var trackArtwork: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build()

        // Luistert naar alle speler-statuswijzigingen: UI, hoofdtelefoon, Bluetooth
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(nowPlaying: Boolean) {
                isPlaying = nowPlaying
                if (nowPlaying) {
                    // Audio actief: foreground service met notificatie
                    startForeground(NOTIFICATION_ID, buildNotification(true))
                } else {
                    // Audio gestopt/gepauzeerd: notificatie zichtbaar laten maar
                    // geen foreground meer (voorkomt "gestopt"-melding van Android)
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(false))
                    stopForegroundCompat(removeNotification = false)
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(launchIntent())
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY" -> {
                val url = intent.getStringExtra("url") ?: return START_STICKY
                currentUrl = url
                playFresh(url)
            }
            "PAUSE"  -> player.pause()
            "RESUME" -> currentUrl?.let { playFresh(it) }
            "STOP"   -> {
                player.stop()
                currentUrl = null
                stopForegroundCompat(removeNotification = true)
            }
            "TRACK_INFO" -> {
                trackTitle = intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: "KrukkexRadio"
                trackArtist = intent.getStringExtra("artist") ?: ""
                val artworkUrl = intent.getStringExtra("artworkUrl") ?: ""
                if (artworkUrl.isNotBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            trackArtwork = BitmapFactory.decodeStream(URL(artworkUrl).openStream())
                        } catch (_: Exception) {}
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(isPlaying))
                    }
                } else {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(isPlaying))
                }
            }
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

    // Houdt notificatie zichtbaar maar verwijdert foreground-status (geen wake-lock)
    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun launchIntent() = PendingIntent.getActivity(
        this, 0,
        packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(playing: Boolean): Notification {
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioService::class.java).apply { action = "PAUSE" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playIntent = PendingIntent.getService(
            this, 2,
            Intent(this, AudioService::class.java).apply { action = "RESUME" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val artwork = trackArtwork ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(trackTitle)
            .setContentText(if (trackArtist.isNotBlank()) trackArtist else if (playing) "Speelt af" else "Gepauzeerd")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(artwork)
            .setContentIntent(launchIntent())
            .setOngoing(playing)
            .setSilent(true)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pauze" else "Spelen",
                if (playing) pauseIntent else playIntent
            )
            .setStyle(MediaStyle().setShowActionsInCompactView(0))
            .build()
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

    override fun onDestroy() {
        mediaSession?.release()
        player.release()
        super.onDestroy()
    }
}
