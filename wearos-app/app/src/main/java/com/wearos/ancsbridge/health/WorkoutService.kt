package com.wearos.ancsbridge.health

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ui.MainActivity

/**
 * Foreground service (type "health") kept alive only while a workout is
 * recording, so exercise updates keep arriving with the screen off.
 * Separate from AncsService so that service never needs body-sensor permission.
 */
class WorkoutService : Service() {

    companion object {
        private const val TAG = "WorkoutService"
        private const val NOTIFICATION_ID = 2
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, AncsApplication.CHANNEL_SERVICE)
            .setContentTitle("Workout in progress")
            .setContentText("Recording for Apple Health")
            .setSmallIcon(R.drawable.ic_fitness)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start health foreground service", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
