package com.aurix.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.voice.WakeWordService
import com.aurix.agent.ui.AurixNav
import com.aurix.agent.ui.AurixTheme
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var agentSettings: AgentSettings
    private val openMission = mutableStateOf<String?>(null)
    private val openVoice = mutableStateOf(false)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        startWakeWordIfEnabled()
        setContent { AurixTheme { AurixNav(openMission.value, openVoice.value) { openMission.value = null; openVoice.value = false } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @javax.inject.Inject lateinit var missionManager: com.aurix.agent.core.agent.MissionManager

    private fun handle(i: Intent?) {
        i?.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() }?.let { cmd ->
            i.removeExtra(EXTRA_COMMAND)
            lifecycleScope.launch { openMission.value = missionManager.create(cmd) }
            return
        }
        openMission.value = i?.getStringExtra(EXTRA_MISSION)
        val voice = i?.getBooleanExtra(EXTRA_VOICE, false) == true
        openVoice.value = voice
        if (voice && Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
    }

    /** Android only allows starting a microphone service while the app is visible, so (re)start it here. */
    private fun startWakeWordIfEnabled() {
        Thread {
            val on = agentSettings.wakeEnabled()
            val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (on && mic) runOnUiThread {
                try { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) } catch (e: Exception) { }
            }
        }.start()
    }

    companion object {
        const val EXTRA_MISSION = "mission_id"
        const val EXTRA_VOICE = "open_voice"
        const val EXTRA_COMMAND = "run_command"
    }
}
