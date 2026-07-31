package com.motorider.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.motorider.activities.MainActivity

class NavigationService : Service() {

    companion object {
        private const val CHANNEL_ID = "MotoRiderNavigationChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "NavigationService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NavigationService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "NavigationService started")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NavigationService destroyed")
        stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MotoRider Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Navigation updates for MotoRider"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MotoRider")
            .setContentText("Navigation active")
            .setSmallIcon(com.motorider.R.drawable.ic_motorcycle)
            .setContentIntent(pendingIntent)
            .build()
    }
}
