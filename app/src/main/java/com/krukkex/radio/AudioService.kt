package com.krukkex.radio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class AudioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            launchIntent ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            "PLAY" -> {
                val url = intent.getStringExtra("url") ?: return START_STICKY
                val mediaItem = MediaItem.fromUri(url)
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
            "PAUSE" -> player.pause()
            "RESUME" -> player.play()
            "STOP"  -> player.stop()
            "VOLUME" -> {
                val volume = intent.getFloatExtra("volume", 1f)
                player.volume = volume.coerceIn(0f, 1f)
            }
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
