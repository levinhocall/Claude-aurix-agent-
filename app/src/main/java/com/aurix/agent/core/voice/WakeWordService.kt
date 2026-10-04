package com.aurix.agent.core.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.aurix.agent.MainActivity
import com.aurix.agent.core.tools.device.AppState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Optional always-on listener for "Hey AURIX" (foreground service, microphone type). The user starts it from Settings
 * while the app is visible (Android requires that). When the phrase is heard it opens the voice screen.
 */
@AndroidEntryPoint
class WakeWordService : Service() {
    @Inject lateinit var engine: VoiceEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun loop() {
        while (true) {
            if (engine.uiActive) { delay(1_500); continue }
            val heard = try {
                engine.listen(8_000) { WakeWord.matches(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(5_000); null
            }
            if (heard != null && WakeWord.matches(heard) && !engine.uiActive) {
                openVoice()
                delay(3_000)
                while (engine.uiActive) delay(1_000)
            } else delay(250)
        }
    }

    private fun openVoice() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_VOICE, true)
        if (AppState.inForeground || Settings.canDrawOverlays(this)) {
            try { startActivity(i); return } catch (e: Exception) { /* fall through to notification */ }
        }
        // Android blocks background activity starts without the overlay permission: ask with a notification instead.
        val pi = PendingIntent.getActivity(this, 8, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_HEARD)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Heard “Hey AURIX”").setContentText("Tap to talk")
            .setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { NotificationManagerCompat.from(this).notify(78, n) } catch (e: SecurityException) { }
    }

    private fun goForeground(): Boolean {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Hey AURIX listening", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_HEARD, "Hey AURIX heard", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_LISTEN)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Listening for “Hey AURIX”")
            .setContentText("Turn off in Settings").setOngoing(true).setContentIntent(open).build()
        return try {
            ServiceCompat.startForeground(this, 77, n, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            true
        } catch (e: Exception) { false }
    }

    private companion object {
        const val CH_LISTEN = "wake_listen"
        const val CH_HEARD = "wake_heard"
    }
}
