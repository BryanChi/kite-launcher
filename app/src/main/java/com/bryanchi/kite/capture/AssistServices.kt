package com.bryanchi.kite.capture

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.RecognitionService.Callback
import android.speech.SpeechRecognizer

/** Lets the user pick Kite as the phone's "digital assistant". Nothing to do here; sessions do the work. */
class KiteAssistService : VoiceInteractionService()

class KiteSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = CaptureSession(this)
}

/** Required by Android for any assistant. Kite has no voice input, so it politely declines. */
class KiteRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
