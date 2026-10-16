package com.aurix.agent

import android.app.Activity
import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch
import android.os.Bundle
import com.aurix.agent.core.tools.device.AppState
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aurix.agent.core.agent.MissionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AurixApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var missions: MissionManager
    @Inject lateinit var triggers: com.aurix.agent.core.proactive.TriggerEngine

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { AppState.visibleActivities++ }
            override fun onActivityStopped(activity: Activity) { AppState.visibleActivities = (AppState.visibleActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        missions.onAppStart()
        // charger events cannot be declared in the manifest on Android 8+; they work while the app process is alive
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                val e = if (i.action == android.content.Intent.ACTION_POWER_CONNECTED) com.aurix.agent.core.proactive.TriggerEvent.CHARGER_ON else com.aurix.agent.core.proactive.TriggerEvent.CHARGER_OFF
                scope.launch { try { triggers.fire(e) } catch (_: Exception) { } }
            }
        }, android.content.IntentFilter().apply { addAction(android.content.Intent.ACTION_POWER_CONNECTED); addAction(android.content.Intent.ACTION_POWER_DISCONNECTED) }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { try { triggers.reschedule() } catch (_: Exception) { } }
    }
}
