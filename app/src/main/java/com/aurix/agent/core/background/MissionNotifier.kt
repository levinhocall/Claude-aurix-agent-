package com.aurix.agent.core.background

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.aurix.agent.MainActivity
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MissionNotifier @Inject constructor(@ApplicationContext private val ctx: Context) {
    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Running missions", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Mission results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openIntent(missionId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_MISSION, missionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, missionId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun foregroundInfo(missionId: String, title: String, text: String): ForegroundInfo {
        ensureChannels()
        val n = NotificationCompat.Builder(ctx, CH_RUN)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(missionId))
            .build()
        val nid = missionId.hashCode()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(nid, n)
    }

    @SuppressLint("MissingPermission")
    fun done(m: MissionEntity) {
        if (m.status != MissionStatus.COMPLETED && m.status != MissionStatus.FAILED) return
        ensureChannels()
        val ok = m.status == MissionStatus.COMPLETED
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(if (ok) "Mission completed" else "Mission failed")
            .setContentText(m.objective.take(120))
            .setAutoCancel(true)
            .setContentIntent(openIntent(m.id))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(m.id.hashCode() + 1, n) } catch (e: SecurityException) { /* notifications not allowed */ }
    }

    private companion object {
        const val CH_RUN = "missions_running"
        const val CH_DONE = "missions_done"
    }
}
