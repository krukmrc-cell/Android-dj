package com.krukkex.radio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Pure Media3 MediaSessionService. De service zelf bevat geen play/pause-logica meer:
 * MainActivity stuurt de speler aan via een MediaController. Media3 beheert daardoor
 * automatisch de foreground-notificatie (met media-knoppen) en de service-lifecycle,
 * zonder de fragiele startForegroundService/5-seconden-deadline.
 *
 * Robuust bij netwerkwisseling (WiFi ↔ 4G/5G): monitort connektiviteit en reconnectet automatisch.
 */
class AudioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var wasPaused = false
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Krappe buffer voor lage latency: 1s min, 3s max — zo dicht mogelijk bij live.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 3000, 500, 1000)
            .build()

        // Custom renderers: voeg een passthrough-AudioProcessor toe die de PCM aftapt
        // voor de visualizer (FFT). De audio zelf blijft ongewijzigd.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(FftAudioProcessor()))
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true) // pauzeer als koptelefoon wordt losgekoppeld
            .build()

        // Altijd live: bij pauze stopt het bufferen niet eindeloos, en bij hervatten
        // gooien we de (tijdens pauze opgebouwde) buffer weg en verbinden we opnieuw,
        // zodat luisteraars nooit achter de live-uitzending aanlopen.
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    wasPaused = true
                } else if (wasPaused) {
                    wasPaused = false
                    val item = player.currentMediaItem ?: return
                    player.setMediaItem(item) // reset → verse live verbinding
                    player.prepare()
                }
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

        // Monitor netwerkveranderingen: bij switch van WiFi ↔ 4G/5G, herverbinden
        registerNetworkCallback(player)
    }

    private fun registerNetworkCallback(player: ExoPlayer) {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager != null) {
            connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    handler.post {
                        // Netwerk beschikbaar → check of we actief spelen en reconnecten nodig is
                        val currentItem = player.currentMediaItem
                        if (currentItem != null && !player.isPlaying && player.playWhenReady) {
                            // Stream is pauzeerend op netwerk, probeer opnieuw
                            player.prepare()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    // Geen onmiddellijke actie; ExoPlayer zal bufferen/hervatten
                    // als het netwerk terugkomt (zie onAvailable)
                }
            }
            connectivityManager.registerDefaultNetworkCallback(connectivityCallback!!)
        }
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
        // Zet de network monitoring uit
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager != null && connectivityCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(connectivityCallback!!)
            } catch (e: Exception) {
                // Ignore: callback was mogelijk niet geregistreerd
            }
        }
        connectivityCallback = null

        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }
}
