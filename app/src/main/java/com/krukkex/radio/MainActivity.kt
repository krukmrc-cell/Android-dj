package com.krukkex.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : AppCompatActivity(), PlaybackController {

    private val websiteUrl = "https://krukkex.nl"

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Minimale injectie — de frontend (AudioPlayer.tsx) roept window.AndroidAudio direct aan.
    private val audioInterceptScript = "(function(){ window.__krukkexReady = true; })();"

    private lateinit var webView: WebView

    // ── Media3 controller ──
    // De controller verbindt met AudioService (MediaSessionService). Media3 promoveert de
    // service zelf naar de voorgrond met notificatie zodra er afgespeeld wordt.
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingPlayUrl: String? = null
    private var currentUrl: String? = null
    private var trackTitle: String = "KrukkexRadio"
    private var trackArtist: String = ""
    private var trackArtwork: String = ""

    // ── Google Cast Framework ──
    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.d("Cast", "Cast session ended")
            castSession = null
        }

        override fun onSessionEnding(session: CastSession) {
            Log.d("Cast", "Cast session ending")
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.d("Cast", "Cast session resumed")
            castSession = session
            updateCastMetadata()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.d("Cast", "Cast session resume failed: $error")
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.d("Cast", "Cast session started: $sessionId")
            castSession = session
            updateCastMetadata()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.d("Cast", "Cast session start failed: $error")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.d("Cast", "Cast session suspended")
        }
    }

    // Leesbaar vanaf de JS-bridge (binder thread) → volatile.
    @Volatile
    private var nativePlaying = false

    // Houd de WebView-UI in sync bij play/pause van buitenaf (koptelefoon, BT, notificatie)
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            nativePlaying = isPlaying
            val state = if (isPlaying) "playing" else "paused"
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('nativeAudioState',{detail:{state:'$state'}}))",
                null
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Google Cast Framework
        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            Log.w("Cast", "Cast Framework not available: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        webView = findViewById(R.id.webView)
        val loadingIndicator = findViewById<View>(R.id.loadingIndicator)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            @Suppress("DEPRECATION")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(WebAppInterface(this, webView), "AndroidAudio")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loadingIndicator.visibility = View.VISIBLE
                view.evaluateJavascript(audioInterceptScript, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                loadingIndicator.visibility = View.GONE
                view.evaluateJavascript(audioInterceptScript, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Onderschep native audio commando's via URL scheme (legacy fallback)
                if (url.startsWith("krukkex://")) {
                    handleNativeScheme(request.url)
                    return true
                }
                return if (url.contains("krukkex.nl")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Log.w("WebView", "Kon externe URL niet openen: $url")
                    }
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("WebView", message.message())
                return true
            }
        }

        // Herstel opgeslagen URL als Android de activity had vernietigd
        savedInstanceState?.getString("current_url")?.let { url ->
            webView.loadUrl(url)
        } ?: webView.loadUrl(websiteUrl)
    }

    // ── MediaController lifecycle ──
    override fun onStart() {
        super.onStart()

        // Register Cast session listener
        castContext?.sessionManager?.addSessionManagerListener(castSessionListener)
        castSession = castContext?.sessionManager?.currentCastSession

        val token = SessionToken(this, ComponentName(this, AudioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = try { future.get() } catch (e: Exception) { null }
            mediaController = controller
            controller?.addListener(playerListener)
            // Initialiseer de status zodat de WebView na een remount meteen klopt.
            nativePlaying = controller?.isPlaying == true
            // Speel een commando af dat binnenkwam voordat de controller verbonden was
            pendingPlayUrl?.let { url -> pendingPlayUrl = null; playUrl(url) }
        }, ContextCompat.getMainExecutor(this))

        // Push FFT-banden uit ExoPlayer naar de WebView voor de visualizer
        FftAudioProcessor.onBands = { bands ->
            val json = bands.joinToString(",", "[", "]")
            runOnUiThread {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('nativeAudioFFT',{detail:$json}))",
                        null
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener)
        FftAudioProcessor.onBands = null
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    // ── PlaybackController — alles op de main thread (controller mag alleen daar) ──
    private fun buildMediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(trackTitle.ifBlank { "KrukkexRadio" })
                    .setArtist(trackArtist.ifBlank { "Live Radio" })
                    .setAlbumTitle("KrukkexRadio")
                    .setArtworkUri(trackArtwork.ifBlank { null }?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    override fun playUrl(url: String) = runOnUiThread {
        currentUrl = url
        val c = mediaController
        if (c == null) {
            pendingPlayUrl = url
            return@runOnUiThread
        }
        c.setMediaItem(buildMediaItem(url))
        c.prepare()
        c.play()
    }

    override fun pausePlayback() = runOnUiThread {
        mediaController?.pause()
    }

    override fun resumePlayback() = runOnUiThread {
        val c = mediaController ?: return@runOnUiThread
        if (c.mediaItemCount > 0) c.play() else currentUrl?.let { playUrl(it) }
    }

    override fun stopPlayback() = runOnUiThread {
        mediaController?.stop()
        currentUrl = null
    }

    override fun setVolume(volume: Float) = runOnUiThread {
        mediaController?.volume = volume.coerceIn(0f, 1f)
    }

    override fun isPlaying(): Boolean = nativePlaying

    override fun updateMetadata(title: String, artist: String, artworkUrl: String) = runOnUiThread {
        trackTitle = title.takeIf { it.isNotBlank() } ?: "KrukkexRadio"
        trackArtist = artist.takeIf { it.isNotBlank() } ?: ""
        trackArtwork = artworkUrl.takeIf { it.isNotBlank() } ?: ""
        val c = mediaController ?: return@runOnUiThread

        // Update metadata → Chromecast real-time
        if (currentUrl != null) {
            c.setMediaItem(buildMediaItem(currentUrl!!), false)
        }

        // Update Cast metadata
        updateCastMetadata()

        Log.d("Android Audio", "Metadata: $trackTitle - $trackArtist (artwork: ${trackArtwork.take(50)}...)")
    }

    override fun updateMetadataFull(title: String, artist: String, source: String, artworkUrl: String) = runOnUiThread {
        trackTitle = title.takeIf { it.isNotBlank() } ?: "KrukkexRadio"
        trackArtist = artist.takeIf { it.isNotBlank() } ?: ""
        trackArtwork = artworkUrl.takeIf { it.isNotBlank() } ?: ""
        val c = mediaController ?: return@runOnUiThread

        // Update metadata → Chromecast real-time
        if (currentUrl != null) {
            c.setMediaItem(buildMediaItemFull(currentUrl!!, source), false)
        }

        // Update Cast metadata
        updateCastMetadata()

        Log.d("Android Audio", "Metadata (full): $trackTitle - $trackArtist | $source")
    }

    private fun buildMediaItemFull(url: String, source: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(trackTitle.ifBlank { "KrukkexRadio" })
                    .setArtist(trackArtist.ifBlank { "Live Radio" })
                    .setAlbumTitle(source.ifBlank { "KrukkexRadio" })
                    .setArtworkUri(trackArtwork.ifBlank { null }?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    private fun handleNativeScheme(uri: Uri) {
        when (uri.host?.lowercase()) {
            "play" -> uri.getQueryParameter("url")?.let { playUrl(it) }
            "pause" -> pausePlayback()
            "resume" -> resumePlayback()
            "stop" -> stopPlayback()
        }
    }

    private fun updateCastMetadata() {
        val session = castSession ?: return
        try {
            Log.d("Cast", "Updating Cast metadata: $trackTitle - $trackArtist")
            // Metadata wordt via MediaSession gehandeld door MediaRouter
            // Cast framework pikt automatisch metadata uit MediaSession op
        } catch (e: Exception) {
            Log.w("Cast", "Failed to update Cast metadata: ${e.message}")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Sla huidige URL op zodat we bij terugkeer niet van voren af aan beginnen
        webView.url?.let { outState.putString("current_url", it) }
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()        // pauzeer JS timers, animaties
        webView.pauseTimers()    // stop alle WebView timers
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()       // hervat JS
        webView.resumeTimers()   // hervat timers
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
