package com.krukkex.radio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastContext

/**
 * Brug voor de native vorige/volgende knoppen (notificatie, lockscreen, Bluetooth, auto).
 * De ForwardingPlayer in de service vangt seekToNext/Previous af en roept deze callbacks
 * aan; MainActivity registreert ze en stuurt het door naar de WebView (frontend-skiplogica).
 * Zelfde proces als MainActivity, dus een statische bridge volstaat (zoals FftAudioProcessor).
 */
object SkipBridge {
    @Volatile var onSkipNext: (() -> Unit)? = null
    @Volatile var onSkipPrevious: (() -> Unit)? = null
}

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
    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var wasPaused = false
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    // Markeer de cast-stream als LIVE zodat de Chromecast geen (zinloze) voortgangsbalk
    // toont. Verder identiek aan DefaultMediaItemConverter (incl. customData voor de
    // round-trip), zodat CastPlayer de queue-items correct kan terugvertalen.
    private val liveMediaItemConverter = object : MediaItemConverter {
        private val delegate = DefaultMediaItemConverter()
        override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
            delegate.toMediaItem(mediaQueueItem)

        override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
            val base = delegate.toMediaQueueItem(mediaItem)
            val info = base.media ?: return base
            val liveInfo = MediaInfo.Builder(info.contentId)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType(info.contentType)
                .setMetadata(info.metadata)
                .setCustomData(info.customData)
                .build()
            return MediaQueueItem.Builder(liveInfo)
                .setAutoplay(base.autoplay)
                .build()
        }
    }

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
                // Forceer 16-bit PCM (geen float output): onze FftAudioProcessor doet
                // visualizer-FFT én EQ alleen op het 16-bit pad. Bij een float-sink zou
                // de EQ stil worden overgeslagen. De bron is een MP3-stream (intern al
                // 16-bit-equivalent), dus dit kost geen hoorbare kwaliteit.
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
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
        exoPlayer = player

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

            // Houd de startscherm-widget in sync met track + afspeelstatus.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushWidgetState(player.mediaMetadata, isPlaying)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                pushWidgetState(mediaMetadata, player.isPlaying)
            }
        })

        // Wrap de speler zodat de native vorige/volgende knoppen verschijnen en hun druk
        // wordt doorgegeven aan de frontend-skiplogica (i.p.v. echt te seeken).
        val sessionPlayer = withSkip(player)

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?: Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        // ── Chromecast: CastPlayer naast de ExoPlayer ──
        // Zodra er een cast-sessie beschikbaar is, neemt de CastPlayer de MediaSession
        // over (audio gaat naar de TV); bij verbreken valt de ExoPlayer weer in (lokaal).
        try {
            val ctx = CastContext.getSharedInstance(this)
            val cast = CastPlayer(ctx, liveMediaItemConverter)
            // Ook bij casten de skip-knoppen actief houden.
            val castSessionPlayer = withSkip(cast)
            cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    setCurrentPlayer(castSessionPlayer)
                }

                override fun onCastSessionUnavailable() {
                    setCurrentPlayer(sessionPlayer)
                }
            })
            castPlayer = cast
            // Als de app start terwijl er al gecast wordt, meteen overschakelen.
            if (cast.isCastSessionAvailable) setCurrentPlayer(castSessionPlayer)
        } catch (e: Exception) {
            android.util.Log.w("Cast", "CastPlayer niet beschikbaar: ${e.message}")
        }

        // Monitor netwerkveranderingen: bij switch van WiFi ↔ 4G/5G, herverbinden
        registerNetworkCallback(player)
    }

    /**
     * Wissel de actieve speler van de MediaSession (ExoPlayer ↔ CastPlayer) en draag
     * het huidige item + play-intentie over. Voor een live-stream is positie irrelevant.
     */
    private fun setCurrentPlayer(player: Player) {
        val session = mediaSession ?: return
        val previous = session.player
        if (previous === player) return

        val currentItem = previous.currentMediaItem
        val playWhenReady = previous.playWhenReady

        // Stop de vorige speler (niet releasen — we hergebruiken hem bij terugschakelen).
        previous.stop()
        previous.clearMediaItems()

        session.player = player

        if (currentItem != null) {
            player.setMediaItem(currentItem)
            player.playWhenReady = playWhenReady
            player.prepare()
        }
    }

    /**
     * Wrap een speler zodat de standaard vorige/volgende transport-commando's altijd
     * beschikbaar zijn (zo tonen notificatie, lockscreen, Bluetooth-headset en auto de
     * knoppen, ook al is het een live-stream met één item). De druk wordt niet als seek
     * uitgevoerd maar doorgegeven aan de frontend-skiplogica via [SkipBridge].
     */
    private fun withSkip(delegate: Player): Player = object : ForwardingPlayer(delegate) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun seekToNext() { SkipBridge.onSkipNext?.invoke() }
        override fun seekToNextMediaItem() { SkipBridge.onSkipNext?.invoke() }
        override fun seekToPrevious() { SkipBridge.onSkipPrevious?.invoke() }
        override fun seekToPreviousMediaItem() { SkipBridge.onSkipPrevious?.invoke() }
    }

    // Duw de huidige now-playing-info naar de startscherm-widget(s).
    private fun pushWidgetState(metadata: MediaMetadata, isPlaying: Boolean) {
        WidgetRenderer.pushState(
            this,
            metadata.title?.toString() ?: "",
            metadata.artist?.toString() ?: "",
            metadata.artworkUri?.toString() ?: "",
            isPlaying
        )
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

        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        exoPlayer?.release()
        exoPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
