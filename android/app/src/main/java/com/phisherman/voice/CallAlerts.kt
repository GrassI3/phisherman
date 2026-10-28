package com.phisherman.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Alerts that work while the phone is at the user's ear: vibration plus a beep played on the
 * voice-call stream (heard in the earpiece). Amber = a few bursts; red = strong continuous
 * vibration and repeating beeps until the user acknowledges, the guard stops, or 60 s pass.
 */
object CallAlerts {
    private const val RED_MAX_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var beeping = false
    @Volatile var redActive = false
        private set

    @Volatile private var shared: Vibrator? = null

    /**
     * One Vibrator for the whole app. Vibrator.cancel() only cancels vibrations started through the
     * same Vibrator object, so starting from the accessibility service and stopping from the app
     * with two different instances left the red alert vibrating forever.
     */
    private fun vibrator(ctx: Context): Vibrator = shared ?: synchronized(this) {
        shared ?: run {
            val app = ctx.applicationContext
            if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)
        }.also { shared = it }
    }

    private fun vibrate(ctx: Context, effect: VibrationEffect) {
        val v = vibrator(ctx)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    /** Suspicious: three firm bursts, repeated three times. */
    fun amber(ctx: Context) {
        if (redActive) return
        val burst = longArrayOf(0, 300, 150, 300, 150, 300, 800)
        val timings = LongArray(burst.size * 3) { burst[it % burst.size] }
        vibrate(ctx, VibrationEffect.createWaveform(timings, -1))
        beep(TonePattern.AMBER)
    }

    /** Dangerous: continuous strong vibration + repeating earpiece beeps until stopped. */
    fun red(ctx: Context) {
        if (redActive) return
        redActive = true
        val timings = longArrayOf(0, 900, 200)
        val amps = intArrayOf(0, 255, 0)
        val effect = if (vibrator(ctx).hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, 0)
        else VibrationEffect.createWaveform(timings, 0)
        vibrate(ctx, effect)
        beep(TonePattern.RED)
        main.postDelayed({ stop(ctx) }, RED_MAX_MS)
    }

    /** Vibration from the open-chat banner goes through the same shared Vibrator. */
    fun pulse(ctx: Context) = vibrate(ctx, VibrationEffect.createWaveform(longArrayOf(0, 400, 150, 400, 150, 400), -1))

    fun stop(ctx: Context) {
        redActive = false
        beeping = false
        main.removeCallbacksAndMessages(null)
        vibrator(ctx).cancel()
        tone?.release()
        tone = null
    }

    private enum class TonePattern { AMBER, RED }

    private fun beep(p: TonePattern) {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
        } catch (e: RuntimeException) {
            return  // tone hardware busy; vibration still runs
        }
        if (p == TonePattern.AMBER) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            return
        }
        beeping = true
        val loop = object : Runnable {
            override fun run() {
                if (!beeping) return
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
                main.postDelayed(this, 2_000)
            }
        }
        main.post(loop)
    }
}
