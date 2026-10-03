package com.aurix.agent

import android.app.Activity
import android.app.Application
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
    }
}
