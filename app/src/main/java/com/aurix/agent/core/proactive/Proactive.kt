package com.aurix.agent.core.proactive

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aurix.agent.MainActivity
import com.aurix.agent.core.agent.MissionManager
import com.aurix.agent.core.approval.AgentSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** True when [deviceName] looks like the user's car (case-insensitive substring; blank pattern never matches). */
fun matchesCar(deviceName: String?, pattern: String): Boolean {
    val p = pattern.trim()
    return p.isNotEmpty() && !deviceName.isNullOrBlank() && deviceName.contains(p, ignoreCase = true)
}

object Proactive {
    private const val CH = "aurix_suggestions"

    /** A tappable suggestion: tapping runs [command] as a normal mission. Nothing runs without that tap, except the user-configured car trigger. */
    fun notify(ctx: Context, id: Int, title: String, text: String, command: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Suggestions", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_COMMAND, command).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }
}

@AndroidEntryPoint
class ProactiveReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: AgentSettings
    @Inject lateinit var manager: MissionManager

    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!settings.proactive()) return@launch
                when (intent.action) {
                    Intent.ACTION_BATTERY_LOW -> Proactive.notify(ctx, 7101, "Battery low", "Tap to check battery status", "battery kitni hai")
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val allowed = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                        if (!allowed) return@launch
                        val dev = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (matchesCar(dev?.name, settings.carBluetooth())) manager.create("driving mode on")
                    }
                }
            } catch (_: Exception) {
            } finally { pending.finish() }
        }
    }
}
