package com.krukkex.radio

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * De frontend (AudioPlayer.tsx) stuurt afspeelcommando's via deze bridge. Ze worden
 * doorgegeven aan de MediaController in MainActivity — Media3 regelt zelf de
 * foreground-notificatie. Geen losse service-intents meer.
 */
interface PlaybackController {
    fun playUrl(url: String)
    fun pausePlayback()
    fun resumePlayback()
    fun stopPlayback()
    fun setVolume(volume: Float)
    fun updateMetadata(title: String, artist: String, artworkUrl: String)
    fun updateMetadataFull(title: String, artist: String, source: String, artworkUrl: String)
    fun isPlaying(): Boolean
    fun startCast()
}

class WebAppInterface(
    private val controller: PlaybackController,
    private val webView: WebView
) {
    @JavascriptInterface
    fun isNativeAndroid(): Boolean = true

    /** Of de native speler op dit moment audio afspeelt (voor UI-sync na een remount). */
    @JavascriptInterface
    fun isNativePlaying(): Boolean = controller.isPlaying()

    /** Open de native Chromecast device-picker. */
    @JavascriptInterface
    fun startCast() {
        controller.startCast()
    }

    @JavascriptInterface
    fun play(url: String) {
        controller.playUrl(url)
        notifyWebView("playing")
    }

    @JavascriptInterface
    fun pause() {
        controller.pausePlayback()
        notifyWebView("paused")
    }

    @JavascriptInterface
    fun resume() {
        controller.resumePlayback()
        notifyWebView("playing")
    }

    @JavascriptInterface
    fun stop() {
        controller.stopPlayback()
        notifyWebView("stopped")
    }

    @JavascriptInterface
    fun updateTrackInfo(title: String, artist: String, artworkUrl: String) {
        controller.updateMetadata(title, artist, artworkUrl)
    }

    @JavascriptInterface
    fun updateTrackInfoFull(title: String, artist: String, source: String, artworkUrl: String) {
        controller.updateMetadataFull(title, artist, source, artworkUrl)
    }

    @JavascriptInterface
    fun setVolume(volume: Float) {
        controller.setVolume(volume)
    }

    @JavascriptInterface
    fun setEqualizerBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0..9) {
            FftAudioProcessor.eqBands[bandIndex] = gainDb.coerceIn(-12f, 12f)
            FftAudioProcessor.updateFilterBand(bandIndex)
        }
    }

    @JavascriptInterface
    fun getEqualizerBands(): String {
        return FftAudioProcessor.eqBands.joinToString(",")
    }

    private fun notifyWebView(state: String) {
        CoroutineScope(Dispatchers.Main).launch {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('nativeAudioState',{detail:{state:'$state'}}))",
                null
            )
        }
    }
}
