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
}

class WebAppInterface(
    private val controller: PlaybackController,
    private val webView: WebView
) {
    @JavascriptInterface
    fun isNativeAndroid(): Boolean = true

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
    fun setVolume(volume: Float) {
        controller.setVolume(volume)
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
