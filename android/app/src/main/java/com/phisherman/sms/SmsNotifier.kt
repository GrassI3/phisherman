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
    private const val CHANNEL = "sms_warnings"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scam message warnings", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Shown right after a suspicious SMS arrives" })
        }
    }

    fun show(ctx: Context, sender: String, body: String, v: Verdict) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val id = body.trim().hashCode()
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
