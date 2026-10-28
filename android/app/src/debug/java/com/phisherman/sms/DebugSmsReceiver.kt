package com.phisherman.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug builds only: feeds a fake SMS through the same handler as SmsReceiver (real SMS cannot be injected over adb). */
class DebugSmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val sender = i.getStringExtra("sender") ?: return
        val body = i.getStringExtra("body") ?: return
        SmsHandler.handle(c, sender, body, goAsync())
    }
}
