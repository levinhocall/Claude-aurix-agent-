package com.aurix.agent

import android.app.Application
import com.aurix.agent.core.agent.MissionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AurixApp : Application() {
    @Inject lateinit var missions: MissionManager

    override fun onCreate() {
        super.onCreate()
        missions.onAppStart()
    }
}
