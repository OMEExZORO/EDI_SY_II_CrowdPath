package com.crowdpath.app.navigation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Text-to-Speech wrapper for navigation guidance.
 *
 * Provides [announce] to speak turn-by-turn instructions.
 * Uses a slightly slowed speech rate for clarity.
 */
class TTSGuide(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.9f) // slightly slower
                isReady = true
                Log.i(TAG, "TTS initialised")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    /**
     * Speak a message, flushing any queued speech.
     */
    fun announce(message: String) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, queuing: $message")
            return
        }
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
    }

    /**
     * Queue a message (does not interrupt current speech).
     */
    fun queue(message: String) {
        if (!isReady) return
        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "nav_q_${System.currentTimeMillis()}")
    }

    /**
     * Release TTS resources.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val TAG = "TTSGuide"
    }
}
