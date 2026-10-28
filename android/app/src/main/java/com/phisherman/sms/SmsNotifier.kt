package com.phisherman.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phisherman.R
import com.phisherman.engine.Level
import com.phisherman.engine.Verdict
import com.phisherman.ui.WarningActivity

object SmsNotifier {
    // v2: the first channel was created without vibration, and channel settings cannot change later
    private const val CHANNEL = "sms_warnings_v2"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("sms_warnings")
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scam message warnings", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Shown right after a suspicious message arrives"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 150, 400)
                })
        }
    }

    fun show(ctx: Context, sender: String, body: String, v: Verdict) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val id = (body.trim() + System.currentTimeMillis() / 60_000).hashCode()  // a repeat a minute later alerts again
        val open = PendingIntent.getActivity(ctx, id, WarningActivity.smsIntent(ctx, body, v),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (v.level == Level.DANGEROUS) "⚠ Scam message from $sender" else "Suspicious message from $sender"
        val reason = v.reasons.firstOrNull()?.textEn ?: "This message looks risky"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\nDo not click links or share OTP, PIN or card details."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
