package com.motorider.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class that wraps TextToSpeech for easier use from NavigationManager.
 * Handles initialization, DND detection, and cleanup.
 */
class TTSManager(private val context: Context) {

    companion object {
        private const val TAG = "TTSManager"
    }

    private var tts: TextToSpeech? = null
    private var isAvailable: Boolean = false
    private val ttsInitialized = AtomicBoolean(false)

    /** Initialize TTS engine. Calls [callback] with success status. */
    fun initialize(callback: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Announce as navigation guidance rather than plain media: Android
                // then ducks the rider's music instead of talking over it, and routes
                // to a connected Bluetooth headset the same way other sat-navs do.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val result = tts?.setLanguage(Locale.getDefault())
                if (result == null || result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS language not available, audio guidance disabled")
                    isAvailable = false
                    ttsInitialized.set(false)
                    callback(false)
                } else {
                    isAvailable = true
                    ttsInitialized.set(true)
                    Log.d(TAG, "TTS initialized successfully")
                    callback(true)
                }
            } else {
                Log.w(TAG, "TTS initialization failed")
                isAvailable = false
                ttsInitialized.set(false)
                callback(false)
            }
        }
    }

    /** Speak text via TTS. Respects Do Not Disturb mode. */
    fun speak(text: String) {
        if (!ttsInitialized.get()) {
            Log.w(TAG, "TTS not initialized, skipping speak: $text")
            return
        }
        if (isDndActive()) {
            Log.d(TAG, "DND active, skipping TTS: $text")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "navigation")
    }

    /** Check if Do Not Disturb is currently active. */
    fun isDndActive(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Check if music stream volume is 0 (muted) or ringer mode is silent
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ||
            audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    /** Stop whatever is currently being spoken, leaving the engine usable. */
    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    /** Shutdown TTS resources. */
    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsInitialized.set(false)
    }

    /** Whether TTS is available for use. */
    fun isAvailable(): Boolean = isAvailable
}
