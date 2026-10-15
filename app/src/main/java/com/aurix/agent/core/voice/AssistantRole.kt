package com.aurix.agent.core.voice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.aurix.agent.MainActivity

/**
 * Makes AURIX selectable as the phone's default digital assistant (Settings -> Default apps -> Digital assistant app).
 * Once chosen, the power-button long-press / home-button hold / lock-screen assist gesture opens the existing voice screen.
 * No new UI: it only launches MainActivity with the same EXTRA_VOICE the wake word already uses.
 */
class AurixVoiceInteractionService : VoiceInteractionService()

class AurixVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AurixVoiceSession(this)
}

private class AurixVoiceSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try { startAssistantActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true)) } catch (_: Exception) { }
        hide()
    }
}

/** Some Android versions require a recognition service in the assistant declaration. It is never used and has no intent filter, so AURIX is not offered as a voice-typing engine. */
class AurixRecognitionStub : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) { } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

object AssistantRole {
    /** true/false when Android lets us read the setting, null when it does not. */
    fun isDefault(ctx: Context): Boolean? = try {
        val v = Settings.Secure.getString(ctx.contentResolver, "assistant")
        if (v == null) null else v.startsWith(ctx.packageName + "/")
    } catch (e: Exception) { null }
}
