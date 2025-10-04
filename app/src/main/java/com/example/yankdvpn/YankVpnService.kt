package com.example.yankdvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class YankVpnService : VpnService() {

    companion object {
        private const val TAG = "YankVpnService"
        private const val CHANNEL_ID = "YankDVPN_Channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== VPN Service Starting ===")
        Log.d(TAG, "Service PID: ${android.os.Process.myPid()}")
        Log.d(TAG, "Intent: $intent")

        try {
            // Start foreground service with notification
            Log.d(TAG, "Starting foreground service...")
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "✅ Foreground service started successfully")

            // Note: We don't establish VPN interface here
            // WireGuard netstack manages its own TUN device

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service: ${e.message}", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VPN Service destroyed")

        // Stop WireGuard netstack
        WireGuardNetstack.stop()
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN permission revoked by user")
        WireGuardNetstack.stop()
        stopSelf()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Yank DVPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Yank DVPN Active")
        .setContentText("VPN connection is active with NAT forwarding")
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()
}
