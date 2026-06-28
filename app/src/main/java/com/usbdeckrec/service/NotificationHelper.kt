package com.usbdeckrec.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.usbdeckrec.R

/**
 * Helper class for creating and managing the recording foreground service notification.
 * Creates a low-importance notification channel ("USB DeckRec Recording") and
 * builds the persistent recording-in-progress notification.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "usb_deckrec_recording"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "USB DeckRec Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recording status notification"
                setSound(null, null)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Build a persistent notification indicating recording is in progress.
     * Includes a "Stop" action button that sends [RecordingService.ACTION_STOP]
     * to the foreground service.
     */
    fun buildRecordingNotification(): android.app.Notification {
        val stopIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("USB DeckRec")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    /**
     * Build a persistent notification indicating monitoring/preview is active
     * (audio is being captured for VU meter and/or phone playback, but not
     * being recorded to a file).
     * Includes a "Stop" action button that sends [RecordingService.ACTION_STOP]
     * to the foreground service.
     */
    fun buildMonitoringNotification(): android.app.Notification {
        val stopIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("USB DeckRec")
            .setContentText("Monitoring connected mixer")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    /**
     * Show a non-ongoing error notification to the user.
     * Used to report recording failures (e.g., insufficient storage).
     */
    fun showRecordingErrorNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val errorChannelId = "usb_deckrec_errors"
            val errorChannel = NotificationChannel(
                errorChannelId,
                "USB DeckRec Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Recording error alerts"
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(errorChannel)
        }

        val notification = NotificationCompat.Builder(context, "usb_deckrec_errors")
            .setContentTitle("USB DeckRec")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setSilent(false)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1002, notification)
    }
}
