package com.krukkex.radio

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class AudioService : MediaSessionService() {

    companion object {
        // Callback naar MainActivity zodat de WebView UI gesynchroniseerd blijft
        @Volatile var playStateCallback: ((Boolean) -> Unit)? = null
    }

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var currentUrl: String? = null
    private var trackTitle: String = "KrukkexRadio"
    private var trackArtist: String = ""
    private var trackArtworkUri: String = ""

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

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setLoadControl(loadControl)
            .build()

        // Alle play/pause events doorsturen naar WebView (ook van hoofdtelefoon/BT)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playStateCallback?.invoke(isPlaying)
            }
        })

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            "PLAY" -> {
                val url = intent.getStringExtra("url") ?: return START_STICKY
                currentUrl = url
                playFresh(url)
            }
            "PAUSE"  -> player.stop()
            "RESUME" -> currentUrl?.let { playFresh(it) }
            "STOP"   -> { player.stop(); currentUrl = null }
            "TRACK_INFO" -> {
                trackTitle  = intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: "KrukkexRadio"
                trackArtist = intent.getStringExtra("artist") ?: ""
                trackArtworkUri = intent.getStringExtra("artworkUrl") ?: ""
                // Zelfde URL → Media3 herstart de stream NIET, alleen metadata update
                if (player.mediaItemCount > 0 && currentUrl != null) {
                    player.replaceMediaItem(0, buildMediaItem(currentUrl!!))
                }
            }
            "VOLUME" -> player.volume = intent.getFloatExtra("volume", 1f).coerceIn(0f, 1f)
        }
        return START_STICKY
    }

    private fun playFresh(url: String) {
        player.stop()
        player.setMediaItem(buildMediaItem(url))
        player.prepare()
        player.play()
    }

    private fun buildMediaItem(url: String) = MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(trackTitle)
                .setArtist(trackArtist.takeIf { it.isNotBlank() })
                .setArtworkUri(trackArtworkUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                .build()
        )
        .build()

    override fun onDestroy() {
        mediaSession?.run { player.release(); release(); mediaSession = null }
        super.onDestroy()
    }
}
