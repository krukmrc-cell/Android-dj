package com.krukkex.radio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Pure Media3 MediaSessionService. De service zelf bevat geen play/pause-logica meer:
 * MainActivity stuurt de speler aan via een MediaController. Media3 beheert daardoor
 * automatisch de foreground-notificatie (met media-knoppen) en de service-lifecycle,
 * zonder de fragiele startForegroundService/5-seconden-deadline.
 */
class AudioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Kleine buffer: 1.5s min, 5s max — blijft dicht bij live
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 5000, 500, 500)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true) // pauzeer als koptelefoon wordt losgekoppeld
            .build()

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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // Stop de service netjes als de app wordt weggeveegd terwijl er niets (meer) speelt
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }
}
