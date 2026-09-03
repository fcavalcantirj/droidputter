package com.droidputter.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.droidputter.MainActivity

private const val CHANNEL_ID = "droidputter-link"
private const val NOTIFICATION_ID = 1
private const val EXTRA_SUBTITLE = "subtitle"

/**
 * A bare foreground service whose only job is to hold a persistent notification while the USB
 * link is up -- the elevated process priority that comes with being a foreground service is
 * what keeps the reader thread ([com.droidputter.usb.UsbDpTransport]'s SerialInputOutputManager)
 * and the link's PING/HELLO_ACK traffic alive once the screen turns off, instead of Android
 * freezing the app in the background mid-session. It owns no link logic itself: [MainActivity]
 * starts/stops it purely off [com.droidputter.usb.LinkStatus.state].
 */
class LinkForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Linked"
        startForeground(NOTIFICATION_ID, buildNotification(subtitle))
        return START_STICKY
    }

    private fun buildNotification(subtitle: String): Notification {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Droidputter")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Droidputter link", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        fun start(context: Context, subtitle: String) {
            val intent = Intent(context, LinkForegroundService::class.java).putExtra(EXTRA_SUBTITLE, subtitle)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkForegroundService::class.java))
        }
    }
}
