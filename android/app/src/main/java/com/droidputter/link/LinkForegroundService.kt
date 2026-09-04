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
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.droidputter.MainActivity

private const val CHANNEL_ID = "droidputter-link"
private const val NOTIFICATION_ID = 1
private const val EXTRA_SUBTITLE = "subtitle"
private const val EXTRA_LOCATION = "location"

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
        // The service type mask is what lets the GPS feed keep running with the screen off: without the
        // LOCATION type Android 14+ stops delivering fixes to a backgrounded app. LOCATION is only legal
        // (SecurityException otherwise) while ACCESS_FINE_LOCATION is granted, so it is added only when the
        // caller says the feed is on and the permission is checked here again.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val wantLocation = intent?.getBooleanExtra(EXTRA_LOCATION, false) == true
        if (wantLocation && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(subtitle), type)
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
        /** (Re)starts the service; calling it again while running just updates the notification and the type mask. */
        fun start(context: Context, subtitle: String, location: Boolean = false) {
            val intent = Intent(context, LinkForegroundService::class.java)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_LOCATION, location)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkForegroundService::class.java))
        }
    }
}
