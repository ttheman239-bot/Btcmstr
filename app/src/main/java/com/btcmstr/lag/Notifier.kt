package com.btcmstr.lag

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

object Notifier {
    const val CHANNEL_ID = "btcmstr_signals"
    const val CHANNEL_NAME = "BTC×MSTR Signals"
    private const val NOTIFICATION_ID_BASE = 7001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "แจ้งเตือนเมื่อ Φ(t,τ) ขึ้นถึง threshold (เข้า/ออกออเดอร์)"
                enableVibration(true)
                enableLights(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun postSignalChange(
        context: Context,
        title: String,
        body: String,
        kind: SignalChangeKind,
    ) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .also { if (pi != null) it.setContentIntent(pi) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + kind.ordinal, notification)
        } catch (_: SecurityException) {
            // Permission not granted at runtime — silently ignore.
        }
    }

    enum class SignalChangeKind { ENTRY_LONG, ENTRY_SHORT, EXIT, FLIP }
}
