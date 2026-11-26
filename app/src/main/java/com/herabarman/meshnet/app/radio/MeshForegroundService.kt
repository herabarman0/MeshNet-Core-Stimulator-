package com.herabarman.meshnet.app.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.herabarman.meshnet.app.R

/**
 * Android 14+ compliant Foreground Service (connectedDevice|microphone) keeping multi-radio
 * BLE / Wi-Fi / Nearby / Hotspot mesh transports alive reliably.
 */
class MeshForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "meshnet_radio_channel"
        private const val NOTIF_ID = 2042
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            isRunning = true
        } catch (_: SecurityException) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "MeshNet Radio Fabric", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(ch)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launch?.let {
            val f = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            PendingIntent.getActivity(this, 0, it, f)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("MeshNet Multi-Radio Active")
            .setContentText("BLE · Wi-Fi · Hotspot · Google Nearby সক্রিয়")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
