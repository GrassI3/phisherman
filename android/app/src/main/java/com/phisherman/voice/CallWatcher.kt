package com.phisherman.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.phisherman.data.Prefs

/**
 * Automatic call guard: when a phone call starts, start listening; when it ends, stop.
 * Needs READ_PHONE_STATE (call state only, never the number) and the auto-guard setting on.
 * Note: Android usually gives other apps silence from the microphone during a call; the service
 * measures the input level so the app can tell the user if that is happening on their phone.
 */
object CallWatcher {
    private const val TAG = "CallWatcher"
    private var registered: Any? = null
    @Volatile var inCall = false
        private set

    fun canWatch(ctx: Context) = Build.VERSION.SDK_INT >= 31 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        if (registered != null || !canWatch(ctx) || !Prefs.autoCallGuard(ctx)) return
        val app = ctx.applicationContext
        val tm = app.getSystemService(TelephonyManager::class.java)
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                Log.d(TAG, "call state $state")
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> if (!inCall) { inCall = true; startGuard(app) }
                    TelephonyManager.CALL_STATE_IDLE -> if (inCall) { inCall = false; stopGuard(app) }
                }
            }
        }
        try {
            tm.registerTelephonyCallback(ContextCompat.getMainExecutor(app), cb)
            registered = cb
            Log.i(TAG, "watching calls")
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot watch calls", e)
        }
    }

    fun stop(ctx: Context) {
        val cb = registered as? TelephonyCallback ?: return
        ctx.applicationContext.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(cb)
        registered = null
    }

    /** Live Caption on: Android's own captioning can hear calls, so read its box instead of the mic. */
    fun liveCaptionOn(ctx: Context): Boolean =
        android.provider.Settings.Secure.getInt(ctx.contentResolver, "odi_captions_enabled", 0) == 1

    private fun startGuard(ctx: Context) {
        val mode = CallGuard.state.value.mode
        if (mode == GuardMode.MIC || mode == GuardMode.CAPTION) { Log.i(TAG, "call started; already guarding"); return }
        if (mode == GuardMode.SCRIPT) CallGuard.end(ctx)  // a real call takes over from a demo script
        if (liveCaptionOn(ctx) && com.phisherman.sms.ChatScreenReader.isEnabled(ctx)) {
            // Android gives our microphone silence during calls; captions arrive via ChatScreenReader
            Log.i(TAG, "call started; guarding through Live Caption")
            CallGuard.begin(ctx, GuardMode.CAPTION)
            CallGuardNotifier.updateOngoing(ctx, com.phisherman.engine.Level.SAFE)
            return
        }
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CallGuardService::class.java).setAction(CallGuardService.ACTION_AUTO))
            Log.i(TAG, "call started; call guard requested")
        } catch (e: Exception) {
            // Android 12+ can refuse to start a microphone service from the background
            Log.w(TAG, "could not start call guard automatically", e)
            CallGuard.onError("Android did not allow the call guard to start automatically: ${e.javaClass.simpleName}")
        }
    }

    private fun stopGuard(ctx: Context) {
        when (CallGuard.state.value.mode) {
            GuardMode.MIC -> ctx.startService(Intent(ctx, CallGuardService::class.java).setAction(CallGuardService.ACTION_STOP))
            GuardMode.CAPTION -> CallGuard.end(ctx)
            else -> {}
        }
    }
}
